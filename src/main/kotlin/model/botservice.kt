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
            SmaCrossoverStrategy(shortPeriod = config.shortPeriod, longPeriod = config.longPeriod) to 1.0,
            RsiStrategy(period = config.shortPeriod, oversoldThreshold = 30.0, overboughtThreshold = 70.0) to 0.5,
            MacdCrossoverStrategy(slow = config.longPeriod, fast = config.shortPeriod) to 1.2,
            BollingerBandsStrategy(period = 20, numStdDev = 2.0, requireCrossover = true) to 0.8,
            StochasticStrategy() to 0.7,
            VwapStrategy() to 0.3,
            GoldenCrossStrategy(shortPeriod = 50, longPeriod = 200) to 1.5,
            IchimokuStrategy() to 1.0
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

        when {
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
            actualDir == 0 && currentPosition != 0 -> {
                if (currentPosition == 1) {
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
            }

            actualDir == 1 && currentPosition != 1 -> {
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

            actualDir == 2 -> {
                logger.info("Patience no need to change the position>>>>>>>>........>>>>>>>>>>>>>........>>>>>>>>>>.................>>>>>>>>>>>>>>>")
            }

            else -> {
                if(config.overTrade) {
                    if(!coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo ) && dir != "Neutral") {
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
                        logger.info("Opening another position>>>>>>>>........>>>>>>>>>>>>>........>>>>>>>>>>.................>>>>>>>>>>>>>>>")
                    }
                } else {
                    logger.info("No need Change position for the moment or place new order>>>>>>>>><<<<<<<<<<<>>>>>>>>>>>><<<<<<<>>>>>>>>>>><<<<<<<<<>>>>>>>>>>>")
                }
                return currentPosition
            }
        }
        return null
    }
}
