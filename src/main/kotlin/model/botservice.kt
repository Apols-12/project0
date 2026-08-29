package com.apols.model

import mu.KotlinLogging

class BotService(private val networkService: NetworkService, private val coreFeature: CoreFeature) {

    val canEnterLongPosition = mutableMapOf<String, Boolean>()
    val canEnterShortPosition = mutableMapOf<String, Boolean>()

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig) {

        val predictorConfig = EngineConfig(
            strategy = MacdCrossoverStrategy(fast = config.fast, slow = config.slow, signal = config.signal),
            minRequiredSignals = 1,
            threshold = 0.5
        )

        val engine = PredictionEngine(predictorConfig)
        val prediction = engine.prediction(config, networkService)


        logger.info("The smoothed Model prediction for user ${config.botName} is: $prediction")
        logger.info("can enter long ${canEnterLongPosition[config.botName]}, can enter short ${canEnterShortPosition[config.botName]}")
        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        val position = coreFeature.getOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo).firstOrNull()
        when(prediction) {
            is Prediction.Buy -> {
                if (hasOpenPosition) {
                    if (position!!.side != "Buy") {
                        logger.info("Signal is Buy, closing Short and opening Long position")
                        coreFeature.placeOrderWithTPSL(
                            apiKey = config.apiKey,
                            secret = config.secretKey,
                            side = "Buy",
                            symbol = config.symbol,
                            quantity = config.qty,
                            leverage = config.leverage,
                            takeProfitPercent = config.tpPercent,
                            stopLossPercent = config.slPercent,
                            category = config.category,
                            useDemo = config.demo
                        )
                    } else {
                        logger.info("Already in Long position")
                        canEnterLongPosition[config.botName] = config.overTrade
                        canEnterShortPosition[config.botName] = true
                    }
                } else {
                    if (canEnterLongPosition[config.botName] ?: true) {
                        logger.info("Opening New Long position+++++++++++++++++++++++++++++++++++++++++++++++++")
                        coreFeature.placeOrderWithTPSL(
                            apiKey = config.apiKey,
                            secret = config.secretKey,
                            side = "Buy",
                            symbol = config.symbol,
                            quantity = config.qty,
                            leverage = config.leverage,
                            takeProfitPercent = config.tpPercent,
                            stopLossPercent = config.slPercent,
                            category = config.category,
                            useDemo = config.demo
                        )
                    }
                }
            }

            is Prediction.Sell -> {
                if (hasOpenPosition) {
                    if (position!!.side != "Sell") {
                        logger.info("Signal is Sell, closing Long and opening Short position________++++++++++_________++++++++______")
                        coreFeature.placeOrderWithTPSL(
                            apiKey = config.apiKey,
                            secret = config.secretKey,
                            side = "Sell",
                            symbol = config.symbol,
                            quantity = config.qty,
                            leverage = config.leverage,
                            takeProfitPercent = config.tpPercent,
                            stopLossPercent = config.slPercent,
                            category = config.category,
                            useDemo = config.demo
                        )
                    } else {
                        canEnterShortPosition[config.botName] = config.overTrade
                        canEnterLongPosition[config.botName] = true
                        logger.info("Already in Short position>>>>>>>>>>><<<<<<<<>>>>>>>>><<<<<<<>>>>>>>>>>>")
                    }
                } else {
                    if (canEnterShortPosition[config.botName] ?: true) {
                        logger.info("Opening New Short position+++++++++++++++++++++++++++++++++++++++++++++++++")
                        coreFeature.placeOrderWithTPSL(
                            apiKey = config.apiKey,
                            secret = config.secretKey,
                            side = "Sell",
                            symbol = config.symbol,
                            quantity = config.qty,
                            leverage = config.leverage,
                            takeProfitPercent = config.tpPercent,
                            stopLossPercent = config.slPercent,
                            category = config.category,
                            useDemo = config.demo
                        )
                    }
                }
            }

            is Prediction.Neutral -> {
                if (hasOpenPosition) {
                    coreFeature.closeOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)
                }
                canEnterLongPosition[config.botName] = true
                canEnterShortPosition[config.botName] = true
                logger.info("No Signal, waiting.......................................................")
                logger.info("Closing open position.......................................................")
            }
        }
    }
}
