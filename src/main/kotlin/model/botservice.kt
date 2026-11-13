package com.apols.model

import mu.KotlinLogging

class BotService(private val networkService: NetworkService, private val coreFeature: CoreFeature) {
    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig) {
        val klines = networkService.getKline(
            baseUrl = "https://api.bybit.com/v5/market/kline",
            symbol = config.symbol,
            interval = config.interval,
            limit = 1000
        )

        logger.info("Current ${config.symbol} price is: ${klines.takeLast(1).single().close}")

        val predictor = SignalEngine(cfg = config.signalConfig, fees = config.feeModel)

        val prediction =  predictor.predict(klines)

        logger.info("Prediction is: $prediction")
        logger.info("Prediction strength: ${prediction.strength}, Stop price: ${prediction.stopPrice}, targeted price is: ${prediction.targetPrice}")

        logger.info("The smoothed Model prediction for user ${config.botName} is: ${prediction.direction.name}")
        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        val position = coreFeature.getOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo).firstOrNull()
        when(prediction.direction) {
            Direction.LONG -> {
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
                            tpPrice = prediction.targetPrice,
                            slPrice = prediction.stopPrice,
                            category = config.category,
                            useDemo = config.demo
                        )
                    } else {
                        logger.info("Already in Long position")
                    }
                } else {
                    logger.info("Opening New Long position+++++++++++++++++++++++++++++++++++++++++++++++++")
                    coreFeature.placeOrderWithTPSL(
                        apiKey = config.apiKey,
                        secret = config.secretKey,
                        side = "Buy",
                        symbol = config.symbol,
                        quantity = config.qty,
                        leverage = config.leverage,
                        tpPrice = prediction.targetPrice,
                        slPrice = prediction.stopPrice,
                        category = config.category,
                        useDemo = config.demo
                    )
                }
            }

            Direction.SHORT -> {
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
                            tpPrice = prediction.targetPrice,
                            slPrice = prediction.stopPrice,
                            category = config.category,
                            useDemo = config.demo
                        )
                    } else {
                        logger.info("Already in Short position>>>>>>>>>>><<<<<<<<>>>>>>>>><<<<<<<>>>>>>>>>>>")
                    }
                } else {
                    logger.info("Opening New Short position+++++++++++++++++++++++++++++++++++++++++++++++++")
                    coreFeature.placeOrderWithTPSL(
                        apiKey = config.apiKey,
                        secret = config.secretKey,
                        side = "Sell",
                        symbol = config.symbol,
                        quantity = config.qty,
                        leverage = config.leverage,
                        tpPrice = prediction.targetPrice,
                        slPrice = prediction.stopPrice,
                        category = config.category,
                        useDemo = config.demo
                    )
                }
            }

            Direction.NEUTRAL -> {
                logger.info("No Signal, waiting.......................................................")
            }
        }
    }
}
