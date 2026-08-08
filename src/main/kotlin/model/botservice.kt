package com.apols.model

import mu.KotlinLogging

class BotService(private val networkService: NetworkService, private val coreFeature: CoreFeature) {


    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, currentDir: Int): Int {

        val predictorConfig = EngineConfig(
            strategy = SmaCrossoverStrategy(shortPeriod = config.shortPeriod, longPeriod = config.longPeriod),
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

        val dir = direction[actualDir].toString()

        logger.info("The smoothed Model prediction for user ${config.botName} is: $dir and the actual is ${direction[actualDir]}")

        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        when {

            currentDir == 2 -> {
                coreFeature.closeOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)
                return actualDir
            }

            actualDir == 2 -> {
                logger.info("Wait for clear signal")
            }

            actualDir == currentDir && !config.overTrade && hasOpenPosition -> {
                logger.info("Wait, no need to place a new trade")

            }

            actualDir == currentDir && !config.overTrade && !hasOpenPosition -> {
                logger.info("Please configure over trade")

            }


            actualDir != 2 && !hasOpenPosition && config.overTrade -> {
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


            actualDir != 2 && !hasOpenPosition -> {
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

        return actualDir
    }
}
