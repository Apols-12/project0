package com.apols.model

import mu.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class BotService(private val networkService: NetworkService, private val coreFeature: CoreFeature) {

    @OptIn(ExperimentalTime::class)
    private var firstN: Instant? = null
    private val logger = KotlinLogging.logger("Prediction")

    @OptIn(ExperimentalTime::class)
    suspend fun start(config: BotConfig, positions: MutableList<Int>): Int {

        val predictorConfig = EngineConfig(
            strategy = SmaCrossoverStrategy(shortPeriod = config.shortPeriod, longPeriod = config.longPeriod),
            minRequiredSignals = 1,
            threshold = config.threshold
        )

        val engine = PredictionEngine(predictorConfig)
        val prediction = engine.prediction(config, networkService)

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

        if (positions.count { it == 2 } == 1) {
            firstN = Clock.System.now()
        }

        if (firstN != null && positions.count { it == 2 } >= 2) {
            if (firstN!! > Clock.System.now().minus(config.patienceTime.minutes)) {
                 positions.clear()
            } else {
                positions.remove(2)
            }
        }

        if (positions.contains(0) && positions.contains(1)) positions.clear()

        val smoothed = positions.count { it == actualDir } > config.patienceTime
        val smoothedDir = if (smoothed) actualDir else 2

        val dir = direction[smoothedDir].toString()

        logger.info("The smoothed Model prediction for user ${config.botName} is: $dir and the actual is ${direction[actualDir]}")

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

            positions.size > config.patienceTime + 1  && !hasOpenPosition -> {
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
