package com.apols.model

import mu.KotlinLogging

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
 * Relative Strength Index (14‑period by default).
 * Returns null if data insufficient.
 */
fun List<Kline>.rsi(period: Int = 14): Double? {
    if (size < period + 1) return null
    val gains = mutableListOf<Double>()
    val losses = mutableListOf<Double>()
    for (i in size - period until size) {
        val change = this[i].close - this[i - 1].close
        if (change >= 0) {
            gains.add(change)
            losses.add(0.0)
        } else {
            gains.add(0.0)
            losses.add(-change)
        }
    }
    val avgGain = gains.average()
    val avgLoss = losses.average()
    if (avgLoss == 0.0) return 100.0
    val rs = avgGain / avgLoss
    return 100.0 - (100.0 / (1.0 + rs))
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
        val rsi = klines.rsi() ?: 50.0
        return when {
            shortSma > longSma && rsi > 50 -> Prediction.Buy(0.7)
            shortSma < longSma && rsi < 50 -> Prediction.Sell(0.7)
            else -> Prediction.Neutral
        }
    }
}

/**
 * MACD crossover: signal line crossover.
 */
/*class MacdCrossoverStrategy(
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
}*/


/**
 * Configuration for the engine and its strategies.
 */
data class EngineConfig(
    val strategy: PredictionStrategy, // strategy to weight
    val minRequiredSignals: Int = 2,
    val threshold: Double = 0.5
)

class PredictionEngine(private val engineConfig: EngineConfig) {
    private val logger = KotlinLogging.logger("predictor")

    /**
     * Process a time-sorted list of Klines and return the aggregated prediction.
     * Always returns a valid Prediction, never throws.
     */
    private fun predict(klines: List<Kline>): Prediction {
        logger.debug ("Processing ${klines.size} klines")
        if (klines.isEmpty()) {
            logger.warn("Empty kline list received, returning Neutral")
            return Prediction.Neutral
        }
        return engineConfig.strategy.predict(klines)
    }

    suspend fun prediction(config: BotConfig, networkService: NetworkService): Prediction {
        
  
val klines = networkService.getKline(
                    baseUrl = "https://api.bybit.com/v5/market/kline",
                    symbol = config.symbol,
                    interval = "5",
                    limit = 1000
                )

                val prediction = predict(klines)  
       
    }
}
