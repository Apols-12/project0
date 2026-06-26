package com.apols.model

import mu.KotlinLogging

class BotService(private val candles: NetworkService, private val coreFeature: CoreFeature) {

    private val logger = KotlinLogging.logger("Prediction")

    suspend fun start(config: BotConfig, currentPosition: Int?, positions: MutableList<Int>, confirmations: MutableList<Int>): Int {
        val intervalConfig = config.intervalConfig

        val intervalWeigh = mutableMapOf(
            "15" to intervalConfig.config15m,
            "30" to intervalConfig.config30m,
            "60" to intervalConfig.config60m,
            "120" to intervalConfig.config120m,
            "240" to intervalConfig.config240m
        )

        val signals = mutableMapOf<Class<out Prediction>, Double>()

        var prediction: Prediction
        val intervalSignals = mutableMapOf<String, Double>()
        var totalWeight = 0.0


        val strategies = config.emaConfig.map {
            SmaCrossoverStrategy(shortPeriod = config.shortestPeriod, it.period) to it.weight
        }

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
                val kline = klines.takeLast(1).first()
                val engine = PredictionEngine(predictorConfig)
                val prediction = engine.predict(klines)

                val upConfirmed = kline.open == kline.low
                val downConfirmed = kline.open == kline.high

                if (upConfirmed) confirmations.add(0)
                if (downConfirmed) confirmations.add(1)

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

        logger.info( "Buy ratio: $buyRatio, Sell ratio: $sellRatio from the bot" )

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

        val smoothed = positions.count { it == actualDir } > config.interval.toInt()* 60 * config.patience
        if(positions.size > config.interval.toInt()* 60 + 10) positions.drop(8)
        val confirmUp = confirmations.count { it == 0 } > config.interval.toInt()* 60 * config.patience
        val confirmDown = confirmations.count { it == 1 } > config.interval.toInt()* 60 * config.patience

        val smoothedDirConfirmed = if (smoothed) actualDir else 2

        val smoothedDir = when {

            smoothedDirConfirmed == 0 && confirmDown -> {
                confirmations.clear()
                2
            }

            smoothedDirConfirmed == 1 && confirmUp -> {
                confirmations.clear()
                2
            }

            smoothedDirConfirmed == 2 -> {
                confirmations.clear()
                2
            }

            else -> smoothedDirConfirmed
        }

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

            positions.size > config.interval.toInt() * config.patience + 2  && !hasOpenPosition -> {
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
