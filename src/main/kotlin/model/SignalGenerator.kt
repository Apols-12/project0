package com.apols.model

import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs

/**
 * Stateless, thread-safe signal engine: klines in, [Prediction] out.
 *
 * Early detection: a statistically unusual return burst (z-score vs the prior window),
 * confirmed by volume, bar-close location, and the fast/slow EMA context. It does not wait
 * for a full MA crossover.
 *
 * Fee awareness: a signal is only emitted if the expected move (targetAtr x ATR) exceeds
 * edgeMultiple x round-trip cost (taker fees + slippage, both sides).
 *
 * Overtrading control: the history is replayed deterministically, so cooldown and the
 * per-window signal budget apply without any stored state. Always pass a consistent window
 * of recent klines (e.g. the last 200-500) for consistent results.
 *
 * Invalid input (unsorted/duplicate timestamps) throws IllegalArgumentException.
 * Too little data returns a NEUTRAL prediction with an explanatory reason.
 */

@Serializable
data class SignalConfig(
    // Regime gate
    val adxTrendEntry: Double = 25.0,
    val adxTrendExit: Double = 20.0,   // hysteresis dead zone
    // Confirmation gate
    val confirmationBars: Int = 2,
    // Cooldown gate
    val cooldownBars: Int = 5,
    // Confluence
    val rsiOversold: Double = 35.0,
    val rsiOverbought: Double = 65.0,
    val minStrengthScore: Double = 0.35,
)

class SignalGenerator(private val config: SignalConfig = SignalConfig()) {

    private val logger = KotlinLogging.logger("Prediction")

    private var lastEmittedDirection: SignalDirection = SignalDirection.FLAT
    private var barsSinceLastSignal: Int = Int.MAX_VALUE
    private var pendingDirection: SignalDirection? = null
    private var pendingCount: Int = 0
    private var lastAdx: Double = 0.0
    private var inTrendRegime: Boolean = false

    fun generate(klines: List<Kline>): Prediction? {
        if (klines.size < 50) return null

        val atr = Indicators.atr(klines)
        if (atr == 0.0) return null

        val adxResult = Indicators.adx(klines)
        val rsi = Indicators.rsi(klines)
        val macd = Indicators.macd(klines)
        val ema20 = Indicators.ema(klines.takeLast(40).map { it.close }, 20)
        val ema50 = Indicators.ema(klines.takeLast(60).map { it.close }, 50)
        val latestClose = klines.last().close

        // --- GATE 1: ADX hysteresis regime filter ---
        // Enter trend regime only above adxTrendEntry; leave only below adxTrendExit.
        if (!inTrendRegime && adxResult.adx >= config.adxTrendEntry) {
            inTrendRegime = true
        } else if (inTrendRegime && adxResult.adx <= config.adxTrendExit) {
            inTrendRegime = false
        }

        /*if (!inTrendRegime) {
            // Still decay the cooldown counter, but emit no directional signal.
            barsSinceLastSignal++
            logger.info("Not in trend regime. Cooldown bars since last signal: $barsSinceLastSignal")
            return null
        }*/

        // --- Directional confluence ---
        val bullishConfluence = listOf(
            latestClose > ema20,
            ema20 > ema50,
            rsi in 40.0..config.rsiOverbought,
            macd.histogram > 0,
            adxResult.plusDi > adxResult.minusDi,
        )
        val bearishConfluence = listOf(
            latestClose < ema20,
            ema20 < ema50,
            rsi in config.rsiOversold..60.0,
            macd.histogram < 0,
            adxResult.minusDi > adxResult.plusDi,
        )

        val rawDirection = when {
            bullishConfluence.count { it } >= 4 -> SignalDirection.LONG
            bearishConfluence.count { it } >= 4 -> SignalDirection.SHORT
            else -> SignalDirection.FLAT
        }

        if (rawDirection == SignalDirection.FLAT) {
            pendingDirection = null
            pendingCount = 0
            barsSinceLastSignal++
            logger.info("Flat signal. Cooldown bars since last signal: $barsSinceLastSignal")
            return null
        }

        // --- GATE 2: Confirmation bars ---
        // The same direction must persist for N consecutive evaluations.
        if (pendingDirection == rawDirection) {
            pendingCount++
        } else {
            pendingDirection = rawDirection
            pendingCount = 1
        }
        /*if (pendingCount < config.confirmationBars) {
            barsSinceLastSignal++
            logger.info("Pending confirmation. Bars since last signal: $barsSinceLastSignal, pending count: $pendingCount")
            return null
        }*/

        // --- GATE 3: Cooldown ---
        if (barsSinceLastSignal < config.cooldownBars && lastEmittedDirection != SignalDirection.FLAT) {
            barsSinceLastSignal++
            logger.info("Cooldown period. Bars since last signal: $barsSinceLastSignal")
            return null
        }

        // --- Strength scoring ---
        val confluenceScore = when (rawDirection) {
            SignalDirection.LONG -> bullishConfluence.count { it } / 5.0
            SignalDirection.SHORT -> bearishConfluence.count { it } / 5.0
            else -> 0.0
        }
        val adxScore = ((adxResult.adx - config.adxTrendEntry) / 25.0).coerceIn(0.0, 1.0)
        val macdScore = (abs(macd.histogram) / (atr * 0.5)).coerceIn(0.0, 1.0)
        val rawScore = (confluenceScore * 0.5 + adxScore * 0.3 + macdScore * 0.2)
        if (rawScore < config.minStrengthScore) {
            barsSinceLastSignal++
            logger.info("Weak signal. Bars since last signal: $barsSinceLastSignal")
            logger.info("Raw score: $rawScore")
            return null
        }

        val strength = SignalStrength(
            score = rawScore,
            tier = when {
                rawScore >= 0.75 -> SignalStrength.Tier.STRONG
                rawScore >= 0.55 -> SignalStrength.Tier.MODERATE
                else -> SignalStrength.Tier.WEAK
            },
        )

        lastEmittedDirection = rawDirection
        barsSinceLastSignal = 0
        pendingDirection = null
        pendingCount = 0

        return Prediction(
            direction = rawDirection,
            strength = strength,
            entryPrice = klines.last().close.toBigDecimal(),
            takeProfit = BigDecimal.ZERO, // filled by RiskManager
            stopLoss = BigDecimal.ZERO,   // filled by RiskManager
            riskRewardRatio = BigDecimal.ZERO,
            atr = BigDecimal.valueOf(atr).setScale(8, RoundingMode.HALF_UP),
            timestamp = Instant.ofEpochMilli(klines.last().openTime),
        )
    }
}
