package com.apols.model

import kotlin.math.abs

object Indicators {

    fun trueRange(current: Kline, previousClose: Double?): Double {
        val highLow = (current.high - current.low)
        if (previousClose == null) return highLow
        val highPrevClose = abs((current.high - previousClose))
        val lowPrevClose = abs((current.low - previousClose))
        return maxOf(highLow, highPrevClose, lowPrevClose)
    }

    fun atr(klines: List<Kline>, period: Int = 14): Double {
        if (klines.size <= period) return 0.0
        val trs = klines.mapIndexed { i, k ->
            trueRange(k, if (i > 0) klines[i - 1].close else null)
        }
        // Wilder smoothing
        var atr = trs.take(period).average()
        for (i in period until trs.size) {
            atr = ((atr * (period - 1)) + trs[i]) / period
        }
        return atr
    }

    fun ema(values: List<Double>, period: Int): Double {
        if (values.size < period) return values.lastOrNull() ?: 0.0
        val multiplier = 2.0 / (period + 1)
        var ema = values.take(period).average()
        for (i in period until values.size) {
            ema += (values[i] - ema) * multiplier
        }
        return ema
    }

    fun rsi(klines: List<Kline>, period: Int = 14): Double {
        if (klines.size <= period) return 50.0
        val closes = klines.map { it.close }
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) avgGain += change else avgLoss -= change
        }
        avgGain /= period
        avgLoss /= period
        for (i in period + 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun macd(klines: List<Kline>): MacdResult {
        val closes = klines.map { it.close }
        if (closes.size < 35) return MacdResult(0.0, 0.0, 0.0)
        val ema12 = emaSeries(closes, 12)
        val ema26 = emaSeries(closes, 26)
        val macdLine = ema12.zip(ema26) { a, b -> a - b }
        val signalLine = emaSeries(macdLine, 9)
        val hist = macdLine.last() - signalLine.last()
        return MacdResult(macdLine.last(), signalLine.last(), hist)
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val multiplier = 2.0 / (period + 1)
        val result = mutableListOf(values.first())
        for (i in 1 until values.size) {
            val next = (values[i] - result.last()) * multiplier + result.last()
            result.add(next)
        }
        return result
    }

    fun adx(klines: List<Kline>, period: Int = 14): AdxResult {
        if (klines.size < period * 2) return AdxResult(0.0, 0.0, 0.0)
        val plusDm = mutableListOf<Double>()
        val minusDm = mutableListOf<Double>()
        val tr = mutableListOf<Double>()

        for (i in 1 until klines.size) {
            val current = klines[i]
            val prev = klines[i - 1]
            tr.add(trueRange(current, prev.close))
            val upMove = (current.high - prev.high)
            val downMove = (prev.low - current.low)
            plusDm.add(if (upMove > downMove && upMove > 0) upMove else 0.0)
            minusDm.add(if (downMove > upMove && downMove > 0) downMove else 0.0)
        }

        val smoothedTr = wilderSmooth(tr, period)
        val smoothedPlus = wilderSmooth(plusDm, period)
        val smoothedMinus = wilderSmooth(minusDm, period)

        val plusDi = smoothedPlus.last() / smoothedTr.last() * 100
        val minusDi = smoothedMinus.last() / smoothedTr.last() * 100
        val diSum = plusDi + minusDi
        val dx = if (diSum == 0.0) 0.0 else abs(plusDi - minusDi) / diSum * 100
        // Final ADX smoothing over DX series
        val dxSeries = mutableListOf<Double>()
        for ((i, element) in smoothedTr.withIndex()) {
            val p = smoothedPlus[i] / element * 100
            val m = smoothedMinus[i] / element * 100
            dxSeries.add(abs(p - m) / (p + m) * 100)
        }
        val adxValue = if (dxSeries.size >= period) dxSeries.takeLast(period).average() else dxSeries.average()
        return AdxResult(adxValue, plusDi, minusDi)
    }

    private fun wilderSmooth(values: List<Double>, period: Int): List<Double> {
        if (values.size < period) return values
        var smoothed = values.take(period).sum()
        val result = mutableListOf(smoothed)
        for (i in period until values.size) {
            smoothed = smoothed - smoothed / period + values[i]
            result.add(smoothed)
        }
        return result
    }

    data class MacdResult(val macd: Double, val signal: Double, val histogram: Double)
    data class AdxResult(val adx: Double, val plusDi: Double, val minusDi: Double)
}