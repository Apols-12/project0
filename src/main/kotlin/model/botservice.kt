package com.apols.model

import mu.KotlinLogging

class BotService(private val candles: NetworkService, private val coreFeature: CoreFeature) {

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, currentPosition: Int?, positions: MutableList<Int>): Int? {

        val data = candles.getKline(
            baseUrl = "https://api.bybit.com/v5/market/kline",
            symbol = config.symbol,
            interval = config.interval,
            limit = 1000
        )

        val strategies = listOf(
            SmaCrossoverStrategy(shortPeriod = config.shortPeriod, longPeriod = config.longPeriod) to 0.05,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 5) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 13) to 0.5,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 6) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 7) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 8) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 9) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 10) to 0.5,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 11) to 0.5,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 12) to 0.5,
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

        val smoothed = positions.count { if (actualDir != 2) it == actualDir else false } > (config.interval.toInt())*2
        val smoothedDir = if (smoothed) actualDir else 2
        val dir = direction[smoothedDir].toString()

        logger.info("The Model prediction for user ${config.botName} is: $dir and it current position is: $currentPosition")

        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        when {
            currentPosition == null && smoothedDir == 2 -> {
                logger.info("Patience no need to open a position>>>>>>>>........>>>>>>>>>>>>>........>>>>>>>>>>.................>>>>>>>>>>>>>>>")
                return actualDir
            }

            currentPosition == 2 && smoothedDir == 2 && hasOpenPosition -> {
                coreFeature.closeOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)
                return actualDir
            }

            currentPosition == 2 && smoothedDir == 2 && !hasOpenPosition -> {
                logger.info("<<<<<<<<>>>>>>>>>>>>>>>><<<<<<<>>>>>>>>>............wait for signal")
                return actualDir
            }

            currentPosition != 2 && smoothedDir == 2 -> {
                logger.info("<><><><<<<<<<>>>>>>><<<<<>>>>>><<<<>>>>>>>>Wait for clear signal")
                return actualDir
            }

            currentPosition == null && smoothedDir != 2-> {
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

            currentPosition == 2 && smoothedDir != 2 -> {
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
                positions.clear()
                return actualDir
            }

            currentPosition == 1 && smoothedDir == 0 -> {
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
                positions.clear()
                return actualDir
            }

            smoothedDir == 1 && currentPosition == 0 -> {
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
                positions.clear()
                return actualDir
            }

            config.overTrade && smoothedDir != 2 && !hasOpenPosition -> {
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
                positions.clear()
                return actualDir
            }

            else -> {
                return currentPosition
            }
        }
    }
}
