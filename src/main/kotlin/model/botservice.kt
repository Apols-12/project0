package com.apols.model

import mu.KotlinLogging

class BotService(private val networkService: NetworkService, private val coreFeature: CoreFeature) {

    private var canEnterTrade: Boolean = true
    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig) {

        val predictorConfig = EngineConfig(
            strategy = SmaCrossoverStrategy(shortPeriod = config.shortPeriod, longPeriod = config.longPeriod),
            minRequiredSignals = 1,
            threshold = config.threshold
        )

        val engine = PredictionEngine(predictorConfig)
        val prediction = engine.prediction(config, networkService)


        logger.info("The smoothed Model prediction for user ${config.botName} is: $prediction")

        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        val position = coreFeature.getOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo).firstOrNull()
        when(prediction) {
            is Prediction.Buy -> {
                if (hasOpenPosition) {
                    if (position!!.side != "Buy") {
                        logger.info("Signal is Buy, closing Short and opening Long position")
                        canEnterTrade = false
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
                    }
                } else {
                    if(canEnterTrade) {
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
                        canEnterTrade = false
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
                        logger.info("Already in Short position>>>>>>>>>>><<<<<<<<>>>>>>>>><<<<<<<>>>>>>>>>>>")
                    }
                } else {
                    if(canEnterTrade) {
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
                logger.info("No Signal, waiting.......................................................")
            }
        }
    }
}
