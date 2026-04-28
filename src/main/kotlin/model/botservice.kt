package com.apols.model


import mu.KotlinLogging
import kotlin.compareTo

class BotService(private val candles: NetworkService, private val coreFeature: CoreFeature) {

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, currentPosition: Int?): Int? {

        val data = candles.getKline(
            baseUrl = "https://api.bybit.com/v5/market/kline",
            symbol = config.symbol,
            interval = config.interval,
            limit = 1000
        )

        val processor = Processor(data)
        val enhancedFeature = processor.enhanceKline(longPeriod = config.longPeriod, shortPeriod = config.shortPeriod)

//        val proFeatures = processor.processed(enhancedFeature).zScoreNorm()

        val direction = mapOf(
            0 to "Buy",
            1 to "Sell",
            2 to "Neutral"
        )

        val actualDir = enhancedFeature.map { it.diffEma }.map { if (it > 0.0 ) 0 else 1 }.takeLast(1)[0]

/*        val wFeatures = proFeatures.takeLast(20).flatten()
        val features = wFeatures.map { it.toFloat() }.toFloatArray()
        val predict = coreFeature.predict(features)*/

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

                logger.info("No need Change position for the moment>>>>>>>>><<<<<<<<<<<>>>>>>>>>>>><<<<<<<>>>>>>>>>>><<<<<<<<<>>>>>>>>>>>")
                return currentPosition
            }
        }
        return null
    }
}
