package com.apols.model

import mu.KotlinLogging
import kotlin.math.sqrt

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
 * Simple Moving Average on close prices.
 * Returns null if not enough data.
 */
fun List<Kline>.sma(period: Int): Double? {
    if (size < period) return null
    return takeLast(period).map { it.close }.average()
}

/**
 * Exponential Moving Average on close prices.
 * Uses standard alpha = 2/(period+1).
 */
fun List<Kline>.ema(period: Int): Double? {
    if (isEmpty() || size < period) return null
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
    val fastEma = ema(fastPeriod) ?: return null
    val slowEma = ema(slowPeriod) ?: return null
    val macdLine = fastEma - slowEma

    // Manual calculation of signal line EMA on the MACD line
    // Here we approximate: we need historical MACD values, so we compute for each index.
    // For simplicity, we recompute; in production cache intermediate results.
    val macdHistory = mutableListOf<Double>()
    for (i in slowPeriod - 1 until size) {
        val sublist = take(i + 1)
        val fast = sublist.ema(fastPeriod)!!
        val slow = sublist.ema(slowPeriod)!!
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

/**
 * Standard deviation of close prices over a given period.
 */
fun List<Kline>.stdDev(period: Int): Double? {
    if (size < period) return null
    val prices = takeLast(period).map { it.close }
    val mean = prices.average()
    val squaredDiffs = prices.map { (it - mean) * (it - mean) }
    return sqrt(squaredDiffs.average())
}

/**
 * Simple moving average of volume over a period.
 */
fun List<Kline>.volumeSma(period: Int): Double? {
    if (size < period) return null
    return takeLast(period).map { it.volume }.average()
}

/**
 * VWAP (Volume Weighted Average Price) for the entire series.
 */
fun List<Kline>.vwap(): Double? {
    if (isEmpty()) return null
    var cumulativeTpv = 0.0  // typical price * volume
    var cumulativeVolume = 0.0
    for (k in this) {
        val typicalPrice = (k.high + k.low + k.close) / 3.0
        cumulativeTpv += typicalPrice * k.volume
        cumulativeVolume += k.volume
    }
    return if (cumulativeVolume == 0.0) null else cumulativeTpv / cumulativeVolume
}

/**
 * Stochastic oscillator %K (raw) for a given period.
 * Returns null if not enough data.
 */
fun List<Kline>.stochasticK(period: Int = 14): Double? {
    if (size < period) return null
    val slice = takeLast(period)
    val highestHigh = slice.maxOf { it.high }
    val lowestLow = slice.minOf { it.low }
    val currentClose = last().close
    if (highestHigh - lowestLow == 0.0) return 50.0 // avoid division by zero
    return ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100.0
}

/**
 * Ichimoku components for the most recent candle.
 * Returns null if insufficient data.
 */
data class IchimokuData(
    val tenkanSen: Double,      // (9-high + 9-low)/2
    val kijunSen: Double,       // (26-high + 26-low)/2
    val senkouSpanA: Double,    // (tenkanSen + kijunSen)/2 (for future, but we align to current)
    val senkouSpanB: Double     // (52-high + 52-low)/2 (same alignment)
)

fun List<Kline>.ichimoku(): IchimokuData? {
    if (size < 52) return null
    val nineHigh = takeLast(9).maxOf { it.high }
    val nineLow = takeLast(9).minOf { it.low }
    val twentySixHigh = takeLast(26).maxOf { it.high }
    val twentySixLow = takeLast(26).minOf { it.low }
    val fiftyTwoHigh = takeLast(52).maxOf { it.high }
    val fiftyTwoLow = takeLast(52).minOf { it.low }

    val tenkan = (nineHigh + nineLow) / 2.0
    val kijun = (twentySixHigh + twentySixLow) / 2.0
    val spanA = (tenkan + kijun) / 2.0   // normally shifted 26 periods ahead, we keep raw for alignment
    val spanB = (fiftyTwoHigh + fiftyTwoLow) / 2.0

    // To check cloud position at the current time, we need the cloud values calculated 26 periods ago.
    // That is, cloud at time T is determined by data up to T-26. We'll reconstruct that in the strategy.
    // So we return the components and let the strategy do the shift.
    return IchimokuData(tenkan, kijun, spanA, spanB)
}

/**
 * Returns the Ichimoku cloud values *at the time of the last candle*,
 * i.e. calculated 26 periods before the end.
 */
fun List<Kline>.cloudNow(): Pair<Double, Double>? {
    // Need enough data to look back 26 extra candles on top of the required 52.
    // We'll compute on (size - 26) candles to get the cloud that is now current.
    if (size < 52 + 26) return null
    val historical = take(size - 26)   // data up to 26 periods ago
    val ichi = historical.ichimoku() ?: return null
    return Pair(ichi.senkouSpanA, ichi.senkouSpanB)
}


class BollingerBandsStrategy(
    private val period: Int = 20,
    private val numStdDev: Double = 2.0,
    private val requireCrossover: Boolean = true
) : PredictionStrategy {

    override fun predict(klines: List<Kline>): Prediction {
        val sma = klines.sma(period) ?: return Prediction.Neutral
        val std = klines.stdDev(period) ?: return Prediction.Neutral
        val upper = sma + numStdDev * std
        val lower = sma - numStdDev * std
        val lastClose = klines.last().close

        if (!requireCrossover) {
            // Simple threshold
            return when {
                lastClose <= lower -> Prediction.Buy(0.65)
                lastClose >= upper -> Prediction.Sell(0.65)
                else -> Prediction.Neutral
            }
        }

        // Crossover detection requires previous candle
        if (klines.size < period + 1) return Prediction.Neutral
        val prevClose = klines[klines.size - 2].close
        val prevUpper = /* can recalc or reuse? To keep it pure we recalc on previous list */
            klines.dropLast(1).let { prevList ->
                val prevSma = prevList.sma(period) ?: return Prediction.Neutral
                val prevStd = prevList.stdDev(period) ?: return Prediction.Neutral
                prevSma + numStdDev * prevStd
            }
        val prevLower = klines.dropLast(1).let { prevList ->
            val prevSma = prevList.sma(period) ?: return Prediction.Neutral
            val prevStd = prevList.stdDev(period) ?: return Prediction.Neutral
            prevSma - numStdDev * prevStd
        }

        // Buy: previous close ≤ lower band AND current close > lower band
        if (prevClose <= prevLower && lastClose > lower) return Prediction.Buy(0.75)
        // Sell: previous close ≥ upper band AND current close < upper band
        if (prevClose >= prevUpper && lastClose < upper) return Prediction.Sell(0.75)

        return Prediction.Neutral
    }
}

class StochasticStrategy(
    private val kPeriod: Int = 14,
    private val dPeriod: Int = 3,
    private val oversold: Double = 20.0,
    private val overbought: Double = 80.0
) : PredictionStrategy {

    override fun predict(klines: List<Kline>): Prediction {
        val k = klines.stochasticK(kPeriod) ?: return Prediction.Neutral
        // Compute %D as SMA of last dPeriod %K values
        if (klines.size < kPeriod + dPeriod) return Prediction.Neutral
        val kValues = mutableListOf<Double>()
        for (i in klines.size - dPeriod until klines.size) {
            val slice = klines.take(i + 1)
            val ki = slice.stochasticK(kPeriod) ?: return Prediction.Neutral
            kValues.add(ki)
        }
        val d = kValues.average()

        // Need previous K and D for crossover
        if (klines.size < kPeriod + dPeriod + 1) return Prediction.Neutral
        val prevKValues = mutableListOf<Double>()
        for (i in klines.size - dPeriod - 1 until klines.size - 1) {
            val slice = klines.take(i + 1)
            val ki = slice.stochasticK(kPeriod) ?: return Prediction.Neutral
            prevKValues.add(ki)
        }
        val prevK = prevKValues.last() // K[t-1]
        val prevD = prevKValues.average() // D[t-1] (simplified)

        // Buy: K crosses above D while both are in oversold zone
        if (prevK <= prevD && k > d && k < oversold && d < oversold) {
            return Prediction.Buy(0.8)
        }
        // Sell: K crosses below D while both are in overbought zone
        if (prevK >= prevD && k < d && k > overbought && d > overbought) {
            return Prediction.Sell(0.8)
        }
        return Prediction.Neutral
    }
}

class VwapStrategy : PredictionStrategy {
    override fun predict(klines: List<Kline>): Prediction {
        val vwap = klines.vwap() ?: return Prediction.Neutral
        val lastClose = klines.last().close
        return when {
            lastClose > vwap -> Prediction.Buy(0.5)
            lastClose < vwap -> Prediction.Sell(0.5)
            else -> Prediction.Neutral
        }
    }
}

class GoldenCrossStrategy(
    private val shortPeriod: Int = 50,
    private val longPeriod: Int = 200
) : PredictionStrategy {
    override fun predict(klines: List<Kline>): Prediction {
        val shortNow = klines.ema(shortPeriod) ?: return Prediction.Neutral
        val longNow = klines.ema(longPeriod) ?: return Prediction.Neutral
        if (klines.size <= longPeriod) return Prediction.Neutral

        // Previous values for crossover detection
        val prevList = klines.dropLast(1)
        val shortPrev = prevList.ema(shortPeriod) ?: return Prediction.Neutral
        val longPrev = prevList.ema(longPeriod) ?: return Prediction.Neutral

        if (shortPrev <= longPrev && shortNow > longNow) {
            return Prediction.Buy(0.9)   // Golden Cross
        }
        if (shortPrev >= longPrev && shortNow < longNow) {
            return Prediction.Sell(0.9)  // Death Cross
        }
        return Prediction.Neutral
    }
}

class IchimokuStrategy : PredictionStrategy {
    override fun predict(klines: List<Kline>): Prediction {
        val cloud = klines.cloudNow() ?: return Prediction.Neutral
        val (spanA, spanB) = cloud
        val lastClose = klines.last().close
        val ichi = klines.ichimoku() ?: return Prediction.Neutral
        val (tenkan, kijun, _, _) = ichi

        val aboveCloud = when {
            spanA > spanB -> lastClose > spanA  // bullish cloud
            spanA < spanB -> lastClose > spanB  // bearish cloud, but still above top
            else -> lastClose > spanA           // flat cloud
        }
        val bullishTK = tenkan > kijun

        if (bullishTK && aboveCloud) return Prediction.Buy(0.85)

        // Also detect TK crossover for stronger signal
        if (klines.size < 10) return Prediction.Neutral
        val prevIchi = klines.dropLast(1).ichimoku() ?: return Prediction.Neutral
        val prevTKcross = prevIchi.tenkanSen - prevIchi.kijunSen
        val currTKcross = tenkan - kijun
        if (prevTKcross <= 0 && currTKcross > 0 && aboveCloud) return Prediction.Buy(0.9)
        if (prevTKcross >= 0 && currTKcross < 0 && !aboveCloud) return Prediction.Sell(0.9)

        return Prediction.Neutral
    }
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
        val shortSma = klines.ema(shortPeriod) ?: return Prediction.Neutral
        val longSma = klines.ema(longPeriod) ?: return Prediction.Neutral
        return when {
            shortSma > longSma -> Prediction.Buy(0.7)
            shortSma < longSma -> Prediction.Sell(0.7)
            else -> Prediction.Neutral
        }
    }
}

/**
 * RSI threshold strategy: oversold / overbought levels.
 */
class RsiStrategy(
    private val period: Int = 14,
    private val oversoldThreshold: Double = 30.0,
    private val overboughtThreshold: Double = 70.0
) : PredictionStrategy {
    override fun predict(klines: List<Kline>): Prediction {
        val rsi = klines.rsi(period) ?: return Prediction.Neutral
        return when {
            rsi < oversoldThreshold -> Prediction.Buy(0.6)
            rsi > overboughtThreshold -> Prediction.Sell(0.6)
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

        val buyRatio = buyScore / totalWeight
        val sellRatio = sellScore / totalWeight

        logger.debug { "Buy ratio: $buyRatio, Sell ratio: $sellRatio" }

        return when {
            buyRatio >= config.biasThreshold && buyRatio > sellRatio ->
                Prediction.Buy(buyRatio)
            sellRatio >= config.biasThreshold && sellRatio > buyRatio ->
                Prediction.Sell(sellRatio)
            else -> Prediction.Neutral
        }
    }
}
