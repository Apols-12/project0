package com.apols.model

import java.math.BigDecimal
import java.time.Instant

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

enum class SignalDirection { LONG, SHORT, FLAT }

data class SignalStrength(
    val score: Double,          // 0.0 .. 1.0
    val tier: Tier,
) {
    enum class Tier { WEAK, MODERATE, STRONG }
}

data class Prediction(
    val direction: SignalDirection,
    val strength: SignalStrength,
    val entryPrice: BigDecimal,
    val takeProfit: BigDecimal,
    val stopLoss: BigDecimal,
    val riskRewardRatio: BigDecimal,
    val atr: BigDecimal,
    val timestamp: Instant,
)

@kotlinx.serialization.Serializable
data class FeeModel(
    val takerBps: Double = 10.0,
    val slippageBps: Double = 2.0,
) {
    init {
        require(takerBps >= 0 && slippageBps >= 0) { "Fees and slippage must be non-negative" }
    }

    val roundTripBps: Double get() = 2.0 * (takerBps + slippageBps)
}