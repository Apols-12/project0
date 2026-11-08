package com.apols.model

import kotlinx.serialization.Serializable

/** One OHLCV candle. Validated on construction, so invalid data never reaches the engine. */
data class Kline(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
) {
    init {
        require(open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() && volume.isFinite()) {
            "Non-finite value in kline at $openTime"
        }
        require(open > 0 && high > 0 && low > 0 && close > 0) { "Prices must be positive (kline at $openTime)" }
        require(volume >= 0) { "Volume must be >= 0 (kline at $openTime)" }
        require(high >= maxOf(open, close) && low <= minOf(open, close)) {
            "Inconsistent OHLC (kline at $openTime)"
        }
    }
}

enum class Direction { LONG, SHORT, NEUTRAL }

/**
 * Result of [SignalEngine.predict], describing the state of the LAST kline.
 *
 * [strength] is a 0..1 heuristic score for ranking signals. It is NOT a calibrated probability.
 * [netEdgeBps] is the expected move minus round-trip fees and slippage.
 */
data class Prediction(
    val direction: Direction,
    val strength: Double,
    val expectedMoveBps: Double,
    val roundTripCostBps: Double,
    val netEdgeBps: Double,
    val entryPrice: Double,
    val stopPrice: Double?,
    val targetPrice: Double?,
    val asOf: Long,
    val reasons: List<String>
)

@Serializable
data class FeeModel(
    val takerBps: Double = 10.0,
    val slippageBps: Double = 2.0
) {
    init { require(takerBps >= 0 && slippageBps >= 0) { "Fees must be >= 0" } }
    val roundTripBps: Double get() = 2 * (takerBps + slippageBps)
}

@Serializable
data class SignalConfig(
    val fastPeriod: Int = 8,
    val slowPeriod: Int = 21,
    val atrPeriod: Int = 14,
    val lookback: Int = 50,            // window for return / volume statistics
    val breakoutLookback: Int = 20,    // prior-bar range used for breakout confirmation
    val zEntry: Double = 2.0,          // return burst, in standard deviations
    val volRatioMin: Double = 1.5,     // volume vs rolling mean
    val minCloseLocation: Double = 0.65, // close must sit in the top (long) / bottom (short) of the bar
    val targetAtr: Double = 2.0,
    val stopAtr: Double = 1.5,
    val edgeMultiple: Double = 2.5,    // expected move must be >= this x round-trip cost
    val cooldownBars: Int = 5,
    val maxSignalsPerWindow: Int = 4,
    val windowBars: Int = 100,
    val allowShort: Boolean = true,
    val maxBars: Int = 500             // only the most recent bars are processed (bounds latency)
) {
    init {
        require(fastPeriod >= 2 && fastPeriod < slowPeriod) { "need 2 <= fastPeriod < slowPeriod" }
        require(atrPeriod >= 2) { "atrPeriod must be >= 2" }
        require(lookback >= 10) { "lookback must be >= 10" }
        require(breakoutLookback >= 2) { "breakoutLookback must be >= 2" }
        require(zEntry > 0 && volRatioMin > 0) { "zEntry and volRatioMin must be > 0" }
        require(minCloseLocation > 0.5 && minCloseLocation < 1.0) { "minCloseLocation must be in (0.5, 1)" }
        require(targetAtr > 0 && stopAtr > 0) { "targetAtr and stopAtr must be > 0" }
        require(edgeMultiple >= 1.0) { "edgeMultiple must be >= 1" }
        require(cooldownBars >= 0 && maxSignalsPerWindow >= 1 && windowBars >= 1) { "invalid throttle settings" }
        require(maxBars >= minBars) { "maxBars must be >= minBars ($minBars)" }
    }

    val minBars: Int get() = maxOf(slowPeriod, lookback, breakoutLookback, atrPeriod) + 2
}
