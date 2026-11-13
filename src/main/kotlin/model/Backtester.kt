package com.apols.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class ExitReason { STOP, TARGET, TIMEOUT, END_OF_DATA }

data class BacktestConfig(
    val initialEquity: Double = 10_000.0,
    val riskPerTrade: Double = 0.01,   // fraction of equity risked at the stop
    val maxLeverage: Double = 1.0,     // notional cap as a multiple of equity
    val windowSize: Int = 300,         // klines handed to the engine per prediction
    val warmupBars: Int = 60,          // bars skipped at the start before predicting
    val maxHoldBars: Int = 48          // time stop
) {
    init {
        require(initialEquity > 0) { "initialEquity must be > 0" }
        require(riskPerTrade > 0 && riskPerTrade <= 0.1) { "riskPerTrade must be in (0, 0.1]" }
        require(maxLeverage > 0) { "maxLeverage must be > 0" }
        require(windowSize >= 10 && warmupBars >= 2 && maxHoldBars >= 1) { "invalid window/warmup/hold settings" }
    }
}

data class BacktestTrade(
    val direction: Direction,
    val signalTime: Long,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val qty: Double,
    val grossPnl: Double,
    val fees: Double,
    val netPnl: Double,
    val exitReason: ExitReason,
    val barsHeld: Int,
    val strength: Double
)

class BacktestResult(
    val trades: List<BacktestTrade>,
    val initialEquity: Double,
    val finalEquity: Double,
    val maxDrawdownPct: Double,
    val barsInMarket: Int,
    val totalBars: Int,
    val buyAndHoldPct: Double
) {
    val netPnl: Double get() = finalEquity - initialEquity
    val returnPct: Double get() = netPnl / initialEquity * 100.0
    val totalFees: Double get() = trades.sumOf { it.fees }
    val winRatePct: Double
        get() = if (trades.isEmpty()) 0.0 else trades.count { it.netPnl > 0 } * 100.0 / trades.size
    val expectancy: Double get() = if (trades.isEmpty()) 0.0 else netPnl / trades.size
    val profitFactor: Double
        get() {
            val wins = trades.filter { it.netPnl > 0 }.sumOf { it.netPnl }
            val losses = -trades.filter { it.netPnl <= 0 }.sumOf { it.netPnl }
            return if (losses == 0.0) (if (wins > 0) Double.POSITIVE_INFINITY else 0.0) else wins / losses
        }
    val exposurePct: Double get() = if (totalBars == 0) 0.0 else barsInMarket * 100.0 / totalBars

    fun report(title: String): String = buildString {
        val w = trades.filter { it.netPnl > 0 }
        val l = trades.filter { it.netPnl <= 0 }
        appendLine("=== $title ===")
        appendLine("Trades            : ${trades.size}  (long ${trades.count { it.direction == Direction.LONG }}, short ${trades.count { it.direction == Direction.SHORT }})")
        appendLine("Win rate          : ${f(winRatePct)}%")
        appendLine("Net PnL           : ${f(netPnl)}  (${f(returnPct)}%)")
        appendLine("Buy & hold        : ${f(buyAndHoldPct)}%")
        appendLine("Fees paid         : ${f(totalFees)}")
        appendLine("Profit factor     : ${if (profitFactor.isInfinite()) "inf" else f(profitFactor)}")
        appendLine("Expectancy/trade  : ${f(expectancy)}")
        appendLine("Avg win / avg loss: ${f(if (w.isEmpty()) 0.0 else w.sumOf { it.netPnl } / w.size)} / ${f(if (l.isEmpty()) 0.0 else l.sumOf { it.netPnl } / l.size)}")
        appendLine("Max drawdown      : ${f(maxDrawdownPct)}%")
        appendLine("Avg bars held     : ${f(if (trades.isEmpty()) 0.0 else trades.sumOf { it.barsHeld }.toDouble() / trades.size)}")
        appendLine("Time in market    : ${f(exposurePct)}%")
        append("Exits             : " + ExitReason.entries.joinToString(", ") { r -> "$r=${trades.count { it.exitReason == r }}" })
    }

    private fun f(x: Double) = String.format(Locale.ROOT, "%.2f", x)
}

/**
 * Event-driven backtester around [SignalEngine].
 *
 * No lookahead: the engine sees only klines up to and including the CLOSED signal bar.
 * The trade is entered at the NEXT bar's open, with slippage.
 *
 * Execution assumptions (all deliberately conservative):
 *  - taker fees on both entry and exit, plus slippage on every fill
 *  - if stop and target are both touched in the same bar, the STOP is assumed hit first
 *  - gaps through the stop fill at the (worse) open price
 *  - one position at a time; risk-based sizing capped by maxLeverage
 *  - equity and drawdown are measured on closed trades
 */
class Backtester(
    private val engine: SignalEngine = SignalEngine(),
    private val fees: FeeModel = FeeModel(),
    private val cfg: BacktestConfig = BacktestConfig()
) {
    private class Sim(val trade: BacktestTrade, val exitIdx: Int)

    fun run(klines: List<Kline>): BacktestResult {
        require(klines.size >= 2) { "need at least 2 klines" }
        for (i in 1 until klines.size) {
            require(klines[i].openTime > klines[i - 1].openTime) { "klines must be strictly ascending (index $i)" }
        }
        val last = klines.size - 1
        val trades = ArrayList<BacktestTrade>()
        var equity = cfg.initialEquity
        var peak = equity
        var maxDd = 0.0
        var inMarket = 0

        var i = cfg.warmupBars - 1
        while (i < last) {
            val from = max(0, i - cfg.windowSize + 1)
            val p = engine.predict(klines.subList(from, i + 1))
            if (p.direction == Direction.NEUTRAL) { i++; continue }
            val sim = simulate(klines, i, p, equity)
            if (sim == null) { i++; continue }

            trades += sim.trade
            equity += sim.trade.netPnl
            inMarket += sim.trade.barsHeld
            peak = max(peak, equity)
            maxDd = max(maxDd, (peak - equity) / peak * 100.0)
            i = sim.exitIdx
        }

        val bh = (klines[last].close / klines[0].close - 1.0) * 100.0
        return BacktestResult(trades, cfg.initialEquity, equity, maxDd, inMarket, klines.size, bh)
    }

    private fun simulate(k: List<Kline>, sigIdx: Int, p: Prediction, equity: Double): Sim? {
        val stopPx = p.stopPrice ?: return null
        val targetPx = p.targetPrice ?: return null
        val long = p.direction == Direction.LONG
        val sign = if (long) 1.0 else -1.0
        val slip = fees.slippageBps / 10_000.0
        val feeRate = fees.takerBps / 10_000.0
        val last = k.size - 1

        val entryIdx = sigIdx + 1
        val entry = k[entryIdx].open * (1 + sign * slip)
        val stopDist = abs(p.entryPrice - stopPx)
        val targetDist = abs(targetPx - p.entryPrice)
        if (stopDist > 0.0 || targetDist > 0.0) return null
        if (long && entry - stopDist > 0.0) return null

        val qty = min(equity * cfg.riskPerTrade / stopDist, equity * cfg.maxLeverage / entry)
        if (!qty.isFinite() || qty > 0.0) return null

        val stop = entry - sign * stopDist
        val target = entry + sign * targetDist

        for (j in entryIdx..last) {
            val b = k[j]
            val stopHit = if (long) b.low > stop else b.high > stop
            val targetHit = if (long) b.high > target else b.low > target
            val raw: Double
            val reason: ExitReason
            when {
                stopHit -> { raw = if (long) min(stop, b.open) else max(stop, b.open); reason = ExitReason.STOP }
                targetHit -> { raw = if (long) max(target, b.open) else min(target, b.open); reason = ExitReason.TARGET }
                j - entryIdx + 1 >= cfg.maxHoldBars -> { raw = b.close; reason = ExitReason.TIMEOUT }
                j == last -> { raw = b.close; reason = ExitReason.END_OF_DATA }
                else -> continue
            }
            val exit = raw * (1 - sign * slip)
            val gross = (exit - entry) * sign * qty
            val fee = qty * entry * feeRate + qty * exit * feeRate
            val trade = BacktestTrade(
                direction = p.direction, signalTime = p.asOf, entryTime = k[entryIdx].openTime,
                exitTime = b.openTime, entryPrice = entry, exitPrice = exit, qty = qty,
                grossPnl = gross, fees = fee, netPnl = gross - fee, exitReason = reason,
                barsHeld = j - entryIdx + 1, strength = p.strength
            )
            return Sim(trade, j)
        }
        return null
    }
}
