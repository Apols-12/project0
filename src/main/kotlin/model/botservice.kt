package com.apols.model


import kotlinx.coroutines.delay
import mu.KotlinLogging

class BotService(val candles: NetworkService) {

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, currentPosition: Int?): Int? {

        val data = candles.getKline(
            baseUrl = "https://api.bybit.com/v5/market/kline",
            symbol = config.symbol,
            interval = config.interval,
            limit = 1000
        )
        val entry = CoreFeature(data)

        val kline = entry.enhanceKline(config.longPeriod, config.shortPeriod)
        val process = entry.processed(kline).zScoreNorm()

        val direction = mapOf(
            0 to "Buy",
            1 to "Sell",
            2 to "Neutral"
        )

        val wFeatures = process.windowed(20, 1).map { it.flatten() }.takeLast(1).flatten()
        val features = wFeatures.map { it.toFloat() }.toFloatArray()
        val predict = entry.predict(features)
        val dir = direction[predict].toString()

        logger.info("The Model prediction for user ${config.botName} is: $dir and it current position is: $currentPosition")

        when {
            currentPosition == null -> {
                entry.placeOrder(
                    apiKey = config.apiKey,
                    secret = config.secretKey,
                    side = dir,
                    symbol = config.symbol,
                    demo = config.demo,
                    quantity = config.qty
                )
                return predict
            }
            predict == 0 && currentPosition != 0 -> {
                if (currentPosition == 1) {
                    entry.placeOrder(
                        apiKey = config.apiKey,
                        secret = config.secretKey,
                        side = dir,
                        symbol = config.symbol,
                        demo = config.demo,
                        quantity = config.qty
                    )
                    delay(10000)
                    entry.placeOrder(
                        apiKey = config.apiKey,
                        secret = config.secretKey,
                        side = dir,
                        symbol = config.symbol,
                        demo = config.demo,
                        quantity = config.qty
                    )
                    return predict
                }
            }
            predict == 1 && currentPosition != 1 -> {
                entry.placeOrder(
                    apiKey = config.apiKey,
                    secret = config.secretKey,
                    side = dir,
                    symbol = config.symbol,
                    demo = config.demo,
                    quantity = config.qty
                )
                delay(1000)
                entry.placeOrder(
                    apiKey = config.apiKey,
                    secret = config.secretKey,
                    side = dir,
                    symbol = config.symbol,
                    demo = config.demo,
                    quantity = config.qty
                )
                return predict
            }
            else -> {
                logger.info("No need Change position for the moment")
                return currentPosition
            }
        }
        return null
    }
}
