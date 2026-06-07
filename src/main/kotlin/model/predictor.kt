package com.apols.model

import mu.KotlinLogging
import kotlin.math.abs
import kotlin.math.max

/**
 * Sealed interface representing a prediction signal.
 * Confidence is optionally attached for aggregators.
 */

sealed class Prediction {
    data class Buy(val confidence: Double = 1.0) : Prediction()
    data class Sell(val confidence: Double = 1.0) : Prediction()
    object Neutral : Prediction()
}

/**
 * Exponential Moving Average on close prices.
 * Uses standard alpha = 2/(period+1).
 */
fun List<Kline>.ema(period: Int): Double {
    require (isNotEmpty() || size > period)
    val alpha = 2.0 / (period + 1)
    // seed with SMA of first `period` elements
    var ema = take(period).map { it.close }.average()
    for (i in period until size) {
        ema += (this[i].close - ema) * alpha
    }
    return ema
}

/**
 * MACD line, signal line and histogram.
 * Returns null when data is insufficient.
 */
data class MACDResult(
    val macdLine: Double,
    val signalLine: Double,
    val histogram: Double
)

fun List<Kline>.macd(
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9
): MACDResult? {
    val fastEma = ema(fastPeriod)
    val slowEma = ema(slowPeriod)
    val macdLine = fastEma - slowEma

    // Manual calculation of signal line EMA on the MACD line
    // Here we approximate: we need historical MACD values, so we compute for each index.
    // For simplicity, we recompute; in production cache intermediate results.
    val macdHistory = mutableListOf<Double>()
    for (i in slowPeriod - 1 until size) {
        val sublist = take(i + 1)
        val fast = sublist.ema(fastPeriod)
        val slow = sublist.ema(slowPeriod)
        macdHistory.add(fast - slow)
    }
    if (macdHistory.size < signalPeriod) return null
    val signalLine = macdHistory.takeLast(signalPeriod).average() // simplified signal (SMA of MACD)
    // More accurate: compute EMA of macdHistory
    // For a real production implementation, compute with EMA formula.
    // We'll stick with a cleaner approach below.

    // Real EMA-based signal line:
    val alpha = 2.0 / (signalPeriod + 1)
    var signalEma = macdHistory.take(signalPeriod).average()
    for (i in signalPeriod until macdHistory.size) {
        signalEma += (macdHistory[i] - signalEma) * alpha
    }
    return MACDResult(macdLine, signalEma, macdLine - signalEma)
}


interface PredictionStrategy {
    /** Must not throw; returns Neutral on insufficient data. */
    fun predict(klines: List<Kline>): Prediction
}

/**
 * SMA crossover strategy: short SMA vs long SMA.
 */
class SmaCrossoverStrategy(
    private val shortPeriod: Int = 20,
    private val longPeriod: Int = 50
) : PredictionStrategy {
    override fun predict(klines: List<Kline>): Prediction {
        val shortSma = klines.ema(shortPeriod)
        val longSma = klines.ema(longPeriod)
        return when {
            shortSma > longSma -> Prediction.Buy(0.7)
            shortSma < longSma -> Prediction.Sell(0.7)
            else -> Prediction.Neutral
        }
    }
}

/**
 * MACD crossover: signal line crossover.
 */
class MacdCrossoverStrategy(
    private val fast: Int = 12,
    private val slow: Int = 26,
    private val signal: Int = 9
) : PredictionStrategy {
    override fun predict(klines: List<Kline>): Prediction {
        // Need two MACD results to detect crossover
        if (klines.size < slow + signal) return Prediction.Neutral
        val current = klines.macd(fast, slow, signal) ?: return Prediction.Neutral
        val previous = klines.dropLast(1).macd(fast, slow, signal) ?: return Prediction.Neutral
        return when {
            current.macdLine > current.signalLine && previous.macdLine <= previous.signalLine ->
                Prediction.Buy(0.8)
            current.macdLine < current.signalLine && previous.macdLine >= previous.signalLine ->
                Prediction.Sell(0.8)
            else -> Prediction.Neutral
        }
    }
}


/**
 * Configuration for the engine and its strategies.
 */
data class EngineConfig(
    val strategies: List<Pair<PredictionStrategy, Double>>, // strategy to weight
    val minRequiredSignals: Int = 2,
    val biasThreshold: Double = 0.6 // ratio to flip to Buy/Sell
)

class PredictionEngine(private val config: EngineConfig) {
    private val logger = KotlinLogging.logger {}

    /**
     * Process a time-sorted list of Klines and return the aggregated prediction.
     * Always returns a valid Prediction, never throws.
     */
    fun predict(klines: List<Kline>): Prediction {
        logger.debug { "Processing ${klines.size} klines" }
        if (klines.isEmpty()) {
            logger.warn("Empty kline list received, returning Neutral")
            return Prediction.Neutral
        }

        val signals = mutableMapOf<Class<out Prediction>, Double>()
        var totalWeight = 0.0

        for ((strategy, weight) in config.strategies) {
            try {
                val prediction = strategy.predict(klines)
                when (prediction) {
                    is Prediction.Buy -> {
                        signals[Prediction.Buy::class.java] =
                            (signals[Prediction.Buy::class.java] ?: 0.0) + weight * prediction.confidence
                        totalWeight += weight
                    }
                    is Prediction.Sell -> {
                        signals[Prediction.Sell::class.java] =
                            (signals[Prediction.Sell::class.java] ?: 0.0) + weight * prediction.confidence
                        totalWeight += weight
                    }
                    Prediction.Neutral -> { /* no weight */ }
                }
                logger.trace { "Strategy ${strategy::class.simpleName}: $prediction" }
            } catch (e: Exception) {
                logger.error(e) { "Strategy ${strategy::class.simpleName} failed, skipping" }
            }
        }

        if (totalWeight == 0.0 || signals.isEmpty()) {
            logger.info("No valid signals generated, returning Neutral")
            return Prediction.Neutral
        }

        val buyScore = signals[Prediction.Buy::class.java] ?: 0.0
        val sellScore = signals[Prediction.Sell::class.java] ?: 0.0
        val totalSignals = buyScore + sellScore
        logger.info("_____________________________total signals: $totalSignals")

        val buyRatio = buyScore / totalWeight
        val sellRatio = sellScore / totalWeight

        logger.info("Buy ratio: $buyRatio, Sell ratio: $sellRatio")

        return when {
            buyRatio >= config.biasThreshold && buyRatio > sellRatio ->
                Prediction.Buy(buyRatio)
            sellRatio >= config.biasThreshold && sellRatio > buyRatio ->
                Prediction.Sell(sellRatio)
            else -> Prediction.Neutral
        }
    }
}
