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


        val features0 = data.computeAllFeatures(
            smaLong = config.longPeriod,
            smaShort = config.shortPeriod,
            emaLong = config.longPeriod,
            emaShort = config.shortPeriod,
            rsiLong = config.longPeriod,
            rsiShort = config.shortPeriod
        )

//        val kline = entry.enhanceKline(config.longPeriod, config.shortPeriod)
//        val process = entry.processed(kline).zScoreNorm()

        val process = features0.map {
            listOf(it.returnPct, it.volumeSma, it.rsiLong, it.rsiShort, it.signalLine, it.histogram, it.rsiDiff, it.smaDiff, it.emaDiff)
        }.zScoreNorm()

        val direction = mapOf(
            0 to "Buy",
            1 to "Sell",
            2 to "Neutral"
        )

        val wFeatures = process.takeLast(1).flatten()
        val features = wFeatures.map { it.toFloat() }.toFloatArray()
        val predict = coreFeature.predict(features)
        val dir = direction[predict].toString()

        logger.info("The Model prediction for user ${config.botName} is: $dir and it current position is: $currentPosition")

        when {
            currentPosition == null -> {
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
                return predict
            }
            predict == 0 && currentPosition != 0 -> {
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

                    return predict
                }
            }

            predict == 1 && currentPosition != 1 -> {
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

                return predict
            }
            else -> {
                if(!coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo )) {
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
