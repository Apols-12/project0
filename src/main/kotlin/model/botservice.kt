package com.apols.model


import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging

class BotService(val candles: NetworkService) {
    val position =  mutableMapOf<String, Int>()
    val mutex = Mutex()
    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig) {
        mutex.withLock {
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

            val wFeatures = process.takeLast(20).flatten()
            val features = wFeatures.map { it.toFloat() }.toFloatArray()
            val predict = entry.predict(features)
            val dir = direction[predict].toString()
            val currentPosition = position[config.botName]
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
                    position[config.botName] = predict
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
                        position[config.botName] = 0
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
                    position[config.botName] = 1
                }
                else -> {
                    logger.info("No need Change position for the moment")
                }
            }
        }
    }
}
