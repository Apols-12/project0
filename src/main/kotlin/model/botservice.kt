package com.apols.model

import mu.KotlinLogging

class BotService(private val candles: NetworkService, private val coreFeature: CoreFeature) {

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, currentPosition: Int?): Int? {

        val data = candles.getKline(
            baseUrl = "https://api.bybit.com/v5/market/kline",
            symbol = config.symbol,
            interval = config.interval,
            limit = 1000
        )

        val strategies = listOf(
            SmaCrossoverStrategy(shortPeriod = config.shortPeriod, longPeriod = config.longPeriod) to 0.5,
            SmaCrossoverStrategy(shortPeriod = 20, longPeriod = 50) to 0.7,
            SmaCrossoverStrategy(shortPeriod = 50, longPeriod = 100) to 0.7,
            SmaCrossoverStrategy(shortPeriod = 12, longPeriod = 30) to 0.4,
            SmaCrossoverStrategy(shortPeriod = 10, longPeriod = 25) to 0.4,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 10) to 0.4,
            SmaCrossoverStrategy(shortPeriod = 3, longPeriod = 7) to 0.4,
            MacdCrossoverStrategy() to 0.7,
            GoldenCrossStrategy(shortPeriod = 50, longPeriod = 200) to 1.0,
            IchimokuStrategy() to 0.5
        )

        val predictorConfig = EngineConfig(
            strategies = strategies,
            minRequiredSignals = 1,
            biasThreshold = config.threshold
        )
        val predictor = PredictionEngine(predictorConfig)

        val prediction = predictor.predict(data)

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

        logger.info("The Model prediction for user ${config.botName} is: $dir and it current position is: $currentPosition")

        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        when {
            currentPosition == null && actualDir == 2 -> {
                logger.info("Patience no need to open a position>>>>>>>>........>>>>>>>>>>>>>........>>>>>>>>>>.................>>>>>>>>>>>>>>>")
                return actualDir
            }

            currentPosition == 2 && actualDir == 2-> {
                logger.info("_________________________________________________________________waiting for clear signal")
                return actualDir
            }

            currentPosition != 2 && actualDir == 2 -> {
                logger.info("<><><><<<<<<<>>>>>>><<<<<>>>>>><<<<>>>>>>>>Wait for clear signal")
                return actualDir
            }

            currentPosition == null && actualDir != 2-> {
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


            actualDir == 0 && currentPosition == 1 -> {
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

            actualDir == 1 && currentPosition == 0 -> {
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

            config.overTrade && actualDir != 2 && !hasOpenPosition -> {
                logger.info("Over trade is configured___________________________________________ ")
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
                return currentPosition
            }
        }
    }
}
