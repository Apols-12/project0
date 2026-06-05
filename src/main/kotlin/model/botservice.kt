package com.apols.model

import mu.KotlinLogging

class BotService(private val candles: NetworkService, private val coreFeature: CoreFeature) {

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, currentPosition: Int?, positions: MutableList<Int>): Int {
        val intervalWeigh = mutableMapOf("3" to 0.4, "5" to 0.4, "15" to 0.3, "30" to 0.2, "60" to 0.1)
        val signals = mutableMapOf<Class<out Prediction>, Double>()

        var prediction: Prediction
        val intervalSignals = mutableMapOf<String, Double>()
        var totalWeight = 0.0

        val strategies = listOf(
            SmaCrossoverStrategy(shortPeriod = config.shortPeriod, longPeriod = config.longPeriod) to 0.05,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 5) to 0.5,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 13) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 6) to 0.5,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 7) to 0.5,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 8) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 9) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 10) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 11) to 0.2,
            SmaCrossoverStrategy(shortPeriod = 4, longPeriod = 12) to 0.2,
        )

        val predictorConfig = EngineConfig(
            strategies = strategies,
            minRequiredSignals = 1,
            biasThreshold = config.threshold
        )

        for ((interval, weigh) in intervalWeigh) {
            try {
                val klines = candles.getKline(
                    baseUrl = "https://api.bybit.com/v5/market/kline",
                    symbol = config.symbol,
                    interval = config.interval,
                    limit = 1000
                )
                val engine = PredictionEngine(predictorConfig)
                val prediction = engine.predict(klines)
                when(prediction) {
                    is Prediction.Buy -> {
                        intervalSignals[interval] = (intervalSignals[interval] ?: 0.0) +  weigh * prediction.confidence
                        signals[Prediction.Buy::class.java] =
                            (signals[Prediction.Buy::class.java] ?: 0.0) + weigh * prediction.confidence
                        totalWeight += weigh
                    }

                    is Prediction.Sell -> {
                        intervalSignals[interval] = (intervalSignals[interval] ?: 0.0) + weigh * prediction.confidence
                        signals[Prediction.Sell::class.java] =
                            (signals[Prediction.Sell::class.java] ?: 0.0) + weigh * prediction.confidence
                        totalWeight += weigh
                    }

                    is Prediction.Neutral -> { }
                }
                logger.info("Interval $interval __________________prediction $prediction")
            } catch (e: Exception) {
                logger.info("Failed for interval $interval _______________________with exception: ${e.message}")
            }
        }

        if (totalWeight == 0.0 || intervalSignals.isEmpty()) {
            prediction = Prediction.Neutral
            logger.info("No valid signals generated, returning Neutral for now: $prediction")
        }

        val buyScore = signals[Prediction.Buy::class.java] ?: 0.0
        val sellScore = signals[Prediction.Sell::class.java] ?: 0.0

        val buyRatio = buyScore / totalWeight
        val sellRatio = sellScore / totalWeight

        logger.debug { "Buy ratio: $buyRatio, Sell ratio: $sellRatio from the bot" }

        prediction = when {
            buyRatio >= predictorConfig.biasThreshold && buyRatio > sellRatio ->
                Prediction.Buy(buyRatio)
            sellRatio >= predictorConfig.biasThreshold && sellRatio > buyRatio ->
                Prediction.Sell(sellRatio)
            else -> Prediction.Neutral
        }

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

        if(positions.contains(2)) positions.clear()

        val smoothed = positions.count { it == actualDir } > config.interval.toInt() / 3
        val smoothedDir = if (smoothed) actualDir else 2
        val dir = direction[smoothedDir].toString()

        logger.info("The Model prediction for user ${config.botName} is: $dir and it current position is: $currentPosition")

        val hasOpenPosition = coreFeature.hasOpenPosition(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)

        when {

            smoothedDir == 2 && hasOpenPosition -> {
                coreFeature.closeOpenPositions(apiKey = config.apiKey, secret = config.secretKey, symbol = config.symbol, category = config.category, useDemo = config.demo)
                return actualDir
            }

            smoothedDir == 2 && !hasOpenPosition -> {
                logger.info("<<<<<<<<>>>>>>>>>>>>>>>><<<<<<<wait for clear signal>>>>>>>>>")
                return actualDir
            }

            !hasOpenPosition && config.overTrade -> {
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

            positions.size > config.interval.toInt() / 3 + 5 && !hasOpenPosition -> {
                logger.info("<<<<<<<<<<<<<<<<<<<<<<<No over trade configured>>>>>>>>>>>>>>>>>>>>>>>>>>")
                return actualDir
            }

            smoothedDir != 2 && !hasOpenPosition -> {
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
                return actualDir
            }
        }
    }
}
