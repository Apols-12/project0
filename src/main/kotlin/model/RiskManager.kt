package com.apols.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class RiskConfig(
    val atrStopMultiplier: Double = 1.8,
    val atrTakeMultiplier: Double = 3.0,
    val riskPerTradeFraction: Double = 0.01, // 1% of equity
) {
    init {
        require(atrStopMultiplier > 0) { "atrStopMultiplier must be positive" }
        require(atrTakeMultiplier > 0) { "atrTakeMultiplier must be positive" }
        require(riskPerTradeFraction in 0.0001..0.10) { "riskPerTradeFraction must be between 0.01% and 10%" }
    }
}

class RiskManager(private val config: RiskConfig = RiskConfig()) {

    fun applyRisk(prediction: Prediction, leverage: Int): Prediction {
        require(leverage >= 1) { "Leverage must be >= 1" }

        val atr = prediction.atr.toDouble()
        val entry = prediction.entryPrice.toDouble()
        val direction = prediction.direction

        val stopDistance = atr * config.atrStopMultiplier
        val takeDistance = atr * config.atrTakeMultiplier

        val stopLoss: BigDecimal
        val takeProfit: BigDecimal

        when (direction) {
            SignalDirection.LONG -> {
                stopLoss = BigDecimal.valueOf(entry - stopDistance).setScale(8, RoundingMode.HALF_UP)
                takeProfit = BigDecimal.valueOf(entry + takeDistance).setScale(8, RoundingMode.HALF_UP)
            }
            SignalDirection.SHORT -> {
                stopLoss = BigDecimal.valueOf(entry + stopDistance).setScale(8, RoundingMode.HALF_UP)
                takeProfit = BigDecimal.valueOf(entry - takeDistance).setScale(8, RoundingMode.HALF_UP)
            }
            SignalDirection.FLAT -> {
                stopLoss = BigDecimal.ZERO
                takeProfit = BigDecimal.ZERO
            }
        }

        val rr = if (config.atrStopMultiplier > 0) {
            BigDecimal.valueOf(config.atrTakeMultiplier / config.atrStopMultiplier)
        } else BigDecimal.ZERO

        return prediction.copy(
            takeProfit = takeProfit,
            stopLoss = stopLoss,
            riskRewardRatio = rr,
        )
    }

    /**
     * Position size in base asset units.
     * Uses fixed-fractional risk, adjusted for leverage so that the
     * notional exposure is leverage * margin but the loss at stop
     * never exceeds [riskPerTradeFraction] of equity.
     */
    fun positionSize(
        equity: BigDecimal,
        entry: BigDecimal,
        stopLoss: BigDecimal,
        leverage: Int,
    ): BigDecimal {
        val riskAmount = equity.multiply(BigDecimal.valueOf(config.riskPerTradeFraction))
        val perUnitRisk = (entry - stopLoss).abs()
        if (perUnitRisk.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        val rawSize = riskAmount.divide(perUnitRisk, 8, RoundingMode.DOWN)
        // Leverage caps the notional exposure but does not change the risk-based unit count
        // when the stop is already defined. This keeps loss-per-trade constant.
        return rawSize.max(BigDecimal.ZERO)
    }
}