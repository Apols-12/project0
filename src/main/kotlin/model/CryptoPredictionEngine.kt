package com.apols.model

import java.math.BigDecimal

class CryptoPredictionEngine(
    private val signalConfig: SignalConfig = SignalConfig(),
    private val riskConfig: RiskConfig = RiskConfig(),
) {
    private val signalGenerator = SignalGenerator(signalConfig)
    private val riskManager = RiskManager(riskConfig)

    /**
     * Call this with the most recent klines, oldest first.
     * The engine is stateful and is designed to be called once per
     * closed candle. Calling it on every tick would defeat the
     * confirmation and cooldown gates.
     */
    fun predict(klines: List<Kline>, leverage: Int = 10): Prediction? {
        val rawPrediction = signalGenerator.generate(klines) ?: return null
        return riskManager.applyRisk(rawPrediction, leverage)
    }

    fun positionSize(
        equity: BigDecimal,
        prediction: Prediction,
        leverage: Int,
    ): BigDecimal = riskManager.positionSize(
        equity = equity,
        entry = prediction.entryPrice,
        stopLoss = prediction.stopLoss,
        leverage = leverage,
    )
}