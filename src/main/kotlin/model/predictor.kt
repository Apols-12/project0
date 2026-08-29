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

private fun ema0(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty() || period < 1) return emptyList()
    val multiplier = 2.0 / (period + 1)
    val result = mutableListOf<Double>()
    var prv = values.first()
    result.add(prv)

    for (i in 1 until values.size) {
        prv += (values[i] - prv) * multiplier
        result.add(prv)
    }
    return result
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
    val diff: Double,
    val dea: Double,
    val hist: Double
)

fun List<Kline>.macd(
    fastPeriod: Int = 12,
    slowPeriod: Int = 26,
    signalPeriod: Int = 9
): MACDResult {
    require(size >= slowPeriod) {"Not enough data"}
    val closes = this.map { it.close }
    val fastEma = ema0(closes, fastPeriod)
    val slowEma = ema0(closes, slowPeriod)
    val diff = fastEma.zip(slowEma) { f, s -> f - s }
    val dea = ema0(diff, signalPeriod)
    val hist = diff.zip(dea) {d, s -> d - s}
    return MACDResult(diff = diff.last(), dea = dea.last(), hist = hist.last())
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
            shortSma > longSma  -> Prediction.Buy(0.7)
            shortSma < longSma -> Prediction.Sell(0.7)
            else -> Prediction.Neutral
        }
    }
}

/**
 * MACD crossover: signal line crossover.
 */
class MacdCrossoverStrategy(
    private val fast: Int = 50,
    private val slow: Int = 100,
    private val signal: Int = 24
) : PredictionStrategy {
    override fun predict(klines: List<Kline>): Prediction {
        // Need two MACD results to detect crossover
        if (klines.size < slow + signal) return Prediction.Neutral
        val current = klines.macd(fast, slow, signal)
        val previous = klines.dropLast(1).macd(fast, slow, signal)
        return when {
            current.hist > previous.hist  ->
                Prediction.Buy(0.8)
            current.hist < previous.hist ->
                Prediction.Sell(0.8)
            else -> Prediction.Neutral
        }
    }
}


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

    suspend fun prediction(config: BotConfig, networkService: NetworkService): Prediction {

        val klines = networkService.getKline(
            baseUrl = "https://api.bybit.com/v5/market/kline",
            symbol = config.symbol,
            interval = config.interval,
            limit = 1000
        )
        return predict(klines)
    }

    /**
     * Process a time-sorted list of _root_ide_package_.org.example.Kline and return the aggregated prediction.
     * Always returns a valid Prediction, never throws.
     */
    fun predict(klines: List<Kline>): Prediction {
        logger.debug { "Processing ${klines.size} klines" }
        if (klines.isEmpty()) {
            logger.warn("Empty kline list received, returning Neutral")
            return Prediction.Neutral
        }

        return engineConfig.strategy.predict(klines)
    }
}
