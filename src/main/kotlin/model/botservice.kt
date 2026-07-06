package com.apols.model

import mu.KotlinLogging

class BotService(private val networkService: NetworkService, private val coreFeature: CoreFeature) {

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, positions: MutableList<Int>): Int {

        val strategies = config.emaConfig.map {
            SmaCrossoverStrategy(shortPeriod = config.shortestPeriod, it.period) to it.weight
        }

        val predictorConfig = EngineConfig(
            strategies = strategies,
            minRequiredSignals = 1,
            threshold = config.threshold
        )

        val engine = PredictionEngine(predictorConfig)
        val prediction = engine.prediction(config, networkService)

        val direction = mapOf(
            0 to "Buy",
            1 to "Sell",
            2 to "Neutral"
        )

        val actualDir = when(prediction) {
            is Prediction.Buy -> 0
            is Prediction.Sell -> 1
            is Prediction.Neutral -> 2
        }

        val clear = positions.count { it == 2 } > 10
        if (clear) positions.clear()
        if (positions.contains(0) && positions.contains(1)) positions.clear()

        val smoothed = positions.count { it == actualDir } > 60 * config.patienceTime
        val smoothedDir = if (smoothed) actualDir else 2

        val dir = direction[smoothedDir].toString()

        logger.info("The smoothed Model prediction for user ${config.botName} is: $dir and the actual is ${direction.get(actualDir)}")

        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        when {

            smoothedDir == 2 && hasOpenPosition -> {
                coreFeature.closeOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)
                return actualDir
            }

            smoothedDir == 2 && !hasOpenPosition -> {
                logger.info("<<<<<<<<>>>>>>>>>>>>>>>><<<<<<<wait for clear signal>>>>>>>>>")
                return actualDir
            }

            !hasOpenPosition && config.overTrade -> {
                coreFeature.placeOrderWithTPSL(
                    apiKey = config.apiKey,
                    secret = config.secretKey,
                    side = dir,
                    symbol = config.symbol,
                    quantity = config.qty,
                    leverage = config.leverage,
                    takeProfitPercent = config.tpPercent,
                    stopLossPercent = config.slPercent,
                    category = config.category,
                    useDemo = config.demo
                )
                return actualDir
            }

            positions.size > 60 * config.patienceTime + 10  && !hasOpenPosition -> {
                logger.info("<<<<<<<<<<<<<<<<<<<<<<<No over trade configured>>>>>>>>>>>>>>>>>>>>>>>>>>")
                return actualDir
            }

            smoothedDir != 2 && !hasOpenPosition -> {
                coreFeature.placeOrderWithTPSL(
                    apiKey = config.apiKey,
                    secret = config.secretKey,
                    side = dir,
                    symbol = config.symbol,
                    quantity = config.qty,
                    leverage = config.leverage,
                    takeProfitPercent = config.tpPercent,
                    stopLossPercent = config.slPercent,
                    category = config.category,
                    useDemo = config.demo
                )
                return actualDir
            }

            else -> {
                return actualDir
            }
        }
    }
}
