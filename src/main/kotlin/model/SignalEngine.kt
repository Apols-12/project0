package com.apols.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stateless, thread-safe signal engine: klines in, [Prediction] out.
 *
 * Early detection: a statistically unusual return burst (z-score vs the prior window),
 * confirmed by volume, bar-close location, and the fast/slow EMA context. It does not wait
 * for a full MA crossover.
 *
 * Fee awareness: a signal is only emitted if the expected move (targetAtr x ATR) exceeds
 * edgeMultiple x round-trip cost (taker fees + slippage, both sides).
 *
 * Overtrading control: the history is replayed deterministically, so cooldown and the
 * per-window signal budget apply without any stored state. Always pass a consistent window
 * of recent klines (e.g. the last 200-500) for consistent results.
 *
 * Invalid input (unsorted/duplicate timestamps) throws IllegalArgumentException.
 * Too little data returns a NEUTRAL prediction with an explanatory reason.
 */
class SignalEngine(
    private val cfg: SignalConfig = SignalConfig(),
    private val fees: FeeModel = FeeModel()
) {
    private class Candidate(val direction: Direction, val strength: Double, val breakout: Boolean)

    fun predict(klines: List<Kline>): Prediction {
        require(klines.isNotEmpty()) { "klines must not be empty" }
        val data = if (klines.size > cfg.maxBars) klines.subList(klines.size - cfg.maxBars, klines.size) else klines
        for (i in 1 until data.size) {
            require(data[i].openTime > data[i - 1].openTime) {
                "klines must be strictly ascending by openTime (index $i)"
            }
        }
        val last = data[data.size - 1]
        if (data.size < cfg.minBars) {
            return neutral(last, "insufficient data: need at least ${cfg.minBars} klines, got ${data.size}")
        }

        val fast = Ema(cfg.fastPeriod)
        val slow = Ema(cfg.slowPeriod)
        val atr = Ema(cfg.atrPeriod)
        val rets = RollingStats(cfg.lookback)
        val vols = RollingStats(cfg.lookback)
        val accepted = ArrayDeque<Int>()
        var lastAccepted = -1_000_000
        var result = neutral(last, "no evaluation")
        val costBps = fees.roundTripBps
        val required = cfg.edgeMultiple * costBps

        for (i in data.indices) {
            val k = data[i]
            val prevClose = if (i == 0) k.close else data[i - 1].close
            val ret = k.close / prevClose - 1.0
            val tr = if (i == 0) k.high - k.low
            else max(k.high - k.low, max(abs(k.high - prevClose), abs(k.low - prevClose)))

            // Signal inputs use statistics from PRIOR bars only (no self-contamination).
            val sd = rets.std
            val z = if (rets.ready && sd > MIN_STD) (ret - rets.mean) / sd else 0.0
            val volRatio = if (vols.ready && vols.mean > 0.0) k.volume / vols.mean else 0.0

            fast.update(k.close); slow.update(k.close); atr.update(tr)
            rets.add(ret); vols.add(k.volume)

            if (i < cfg.minBars - 1) continue // warm-up
            val isLast = i == data.size - 1

            val c = detect(data, i, z, volRatio, fast.value, slow.value)
            if (c == null) {
                if (isLast) result = neutral(k, "no early signal (z=${f(z)}, volume x${f(volRatio)})")
                continue
            }
            val atrV = atr.value
            if (atrV <= 0.0) {
                if (isLast) result = neutral(k, "zero volatility")
                continue
            }
            val expectedBps = cfg.targetAtr * atrV / k.close * 10_000.0
            if (expectedBps < required) {
                if (isLast) result = neutral(
                    k, "edge too small: expected ${f(expectedBps)} bps < required ${f(required)} bps after fees"
                )
                continue
            }
            while (accepted.isNotEmpty() && i - accepted.first() > cfg.windowBars) accepted.removeFirst()
            if (i - lastAccepted < cfg.cooldownBars) {
                if (isLast) result = neutral(k, "cooldown active (${i - lastAccepted} of ${cfg.cooldownBars} bars)")
                continue
            }
            if (accepted.size >= cfg.maxSignalsPerWindow) {
                if (isLast) result = neutral(k, "signal budget exhausted for window")
                continue
            }
            accepted.addLast(i)
            lastAccepted = i

            if (isLast) {
                val long = c.direction == Direction.LONG
                val entry = k.close
                val stop = if (long) entry - cfg.stopAtr * atrV else entry + cfg.stopAtr * atrV
                val target = if (long) entry + cfg.targetAtr * atrV else max(0.0, entry - cfg.targetAtr * atrV)
                result = Prediction(
                    direction = c.direction,
                    strength = c.strength,
                    expectedMoveBps = expectedBps,
                    roundTripCostBps = costBps,
                    netEdgeBps = expectedBps - costBps,
                    entryPrice = entry,
                    stopPrice = max(0.0, stop),
                    targetPrice = target,
                    asOf = k.openTime,
                    reasons = listOf(
                        "early burst: z=${f(z)}, volume x${f(volRatio)}, breakout=${c.breakout}",
                        "expected ${f(expectedBps)} bps vs cost ${f(costBps)} bps"
                    )
                )
            }
        }
        return result
    }

    private fun detect(data: List<Kline>, i: Int, z: Double, volRatio: Double, fast: Double, slow: Double): Candidate? {
        if (volRatio < cfg.volRatioMin) return null
        val k = data[i]
        val range = k.high - k.low
        val clv = if (range > 0.0) (k.close - k.low) / range else 0.5

        val dir = when {
            z >= cfg.zEntry && clv >= cfg.minCloseLocation && k.close > fast && fast >= slow * 0.999 ->
                Direction.LONG
            cfg.allowShort && z <= -cfg.zEntry && clv <= 1.0 - cfg.minCloseLocation &&
                k.close < fast && fast <= slow * 1.001 -> Direction.SHORT
            else -> return null
        }

        var hh = Double.NEGATIVE_INFINITY
        var ll = Double.POSITIVE_INFINITY
        for (j in max(0, i - cfg.breakoutLookback) until i) {
            hh = max(hh, data[j].high); ll = min(ll, data[j].low)
        }
        val long = dir == Direction.LONG
        val breakout = if (long) k.close > hh else k.close < ll
        val dirClv = if (long) clv else 1.0 - clv
        val strength = 0.4 * min(abs(z) / (2 * cfg.zEntry), 1.0) +
            0.3 * min(volRatio / (2 * cfg.volRatioMin), 1.0) +
            0.2 * ((dirClv - cfg.minCloseLocation) / (1.0 - cfg.minCloseLocation)).coerceIn(0.0, 1.0) +
            (if (breakout) 0.1 else 0.0)
        return Candidate(dir, strength.coerceIn(0.0, 1.0), breakout)
    }

    private fun neutral(k: Kline, reason: String) = Prediction(
        Direction.NEUTRAL, 0.0, 0.0, fees.roundTripBps, 0.0, k.close, null, null, k.openTime, listOf(reason)
    )

    private fun f(x: Double) = String.format(Locale.ROOT, "%.2f", x)

    private companion object { const val MIN_STD = 1e-9 }
}

private class Ema(period: Int) {
    private val a = 2.0 / (period + 1)
    var value = Double.NaN; private set
    fun update(x: Double) { value = if (value.isNaN()) x else value + a * (x - value) }
}

private class RollingStats(private val n: Int) {
    private val buf = DoubleArray(n)
    private var i = 0
    private var size = 0
    private var sum = 0.0
    private var sumSq = 0.0
    fun add(x: Double) {
        if (size == n) { val old = buf[i]; sum -= old; sumSq -= old * old } else size++
        buf[i] = x; sum += x; sumSq += x * x
        i = (i + 1) % n
    }
    val ready: Boolean get() = size == n
    val mean: Double get() = if (size == 0) 0.0 else sum / size
    val std: Double get() = if (size < 2) 0.0 else sqrt(max(sumSq / size - mean * mean, 0.0))
}
