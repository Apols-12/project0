package com.apols.model

import mu.KotlinLogging

/**
 * Sealed interface representing a prediction signal.
 * Confidence is optionally attached for aggregators.
 */


/**
 * Exponential Moving Average on close prices.
 * Uses standard alpha = 2/(period+1).
 */
fun List<Kline>.ema(period: Int): Double {
    require (isNotEmpty() || size > period)
    val alpha = 2.0 / (period + 1)
    // seed with SMA of first `period` elements
    var ema = takeLast(period).map { it.close }.average()
    for (i in period until size) {
        ema += (this[i].close - ema) * alpha
    }
    return ema
}

private fun ema0(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty() || period < 1) return emptyList()
    val multiplier = 2.0 / (period + 1)
    val result = mutableListOf<Double>()
    var prv = values.first()
    result.add(prv)

    for (i in 1 until values.size) {
        prv += (values[i] - prv) * multiplier
        result.add(prv)
    }
    return result
}
/**
 * Relative Strength Index (14‑period by default).
 * Returns null if data insufficient.
 */
fun List<Kline>.rsi(period: Int = 14): Double? {
    if (size < period + 1) return null
    val gains = mutableListOf<Double>()
    val losses = mutableListOf<Double>()
    for (i in size - period until size) {
        val change = this[i].close - this[i - 1].close
        if (change >= 0) {
            gains.add(change)
            losses.add(0.0)
        } else {
            gains.add(0.0)
            losses.add(-change)
        }
    }
    val avgGain = gains.average()
    val avgLoss = losses.average()
    if (avgLoss == 0.0) return 100.0
    val rs = avgGain / avgLoss
    return 100.0 - (100.0 / (1.0 + rs))
}

/**
 * MACD line, signal line and histogram.
 * Returns null when data is insufficient.
 */
data class MACDResult(
    val diff: Double,
    val dea: Double,
    val hist: Double
)

fun List<Kline>.macd(
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9
): MACDResult {
    require(size >= slowPeriod) {"Not enough data"}
    val closes = this.map { it.close }
    val fastEma = ema0(closes, fastPeriod)
    val slowEma = ema0(closes, slowPeriod)
    val diff = fastEma.zip(slowEma) { f, s -> f - s }
    val dea = ema0(diff, signalPeriod)
    val hist = diff.zip(dea) {d, s -> d - s}
    return MACDResult(diff = diff.last(), dea = dea.last(), hist = hist.last())
}

