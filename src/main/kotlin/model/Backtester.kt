package com.apols.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

enum class ExitReason { TARGET, STOP, END_OF_DATA, SIGNAL_EXIT }

data class BacktestTrade(
    val direction: SignalDirection,
    val signalTime: Long,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val stopLoss: BigDecimal,
    val takeProfit: BigDecimal,
    val quantity: BigDecimal,
    val grossPnl: BigDecimal,
    val fees: BigDecimal,
    val netPnl: BigDecimal,
    val returnPct: BigDecimal,
    val exitReason: ExitReason,
)

data class BacktestResult(
    val initialEquity: BigDecimal,
    val finalEquity: BigDecimal,
    val totalReturnPct: BigDecimal,
    val maxDrawdownPct: BigDecimal,
    val winRatePct: BigDecimal,
    val profitFactor: BigDecimal,
    val sharpeRatio: BigDecimal,
    val sortinoRatio: BigDecimal,
    val totalTrades: Int,
    val trades: List<BacktestTrade>,
)

data class BacktestConfig(
    val warmupBars: Int = 100,
    val riskPerTrade: Double = 0.01,
    val feeModel: FeeModel = FeeModel(),
    val slippageFactor: Double = 1.0,
)

class Backtester(
    private val config: BacktestConfig = BacktestConfig(),
    private val signalConfig: SignalConfig = SignalConfig(),
    private val riskConfig: RiskConfig = RiskConfig(),
) {
    init {
        require(config.warmupBars >= 50) { "warmupBars must be at least 50" }
        require(config.riskPerTrade in 0.0001..0.10) { "riskPerTrade must be between 0.01% and 10%" }
        require(config.slippageFactor >= 0) { "slippageFactor must be non-negative" }
    }

    fun run(
        klines: List<Kline>,
        initialEquity: BigDecimal = BigDecimal("10000"),
    ): BacktestResult {
        require(initialEquity > BigDecimal.ZERO) { "initialEquity must be positive" }
        require(klines.size > config.warmupBars) { "Not enough data for backtest warmup" }

        var equity = initialEquity
        var active: ActiveTrade? = null
        val trades = mutableListOf<BacktestTrade>()
        val equityCurve = mutableListOf(initialEquity)
        val returns = mutableListOf<Double>()
        val engine = CryptoPredictionEngine(signalConfig, riskConfig)

        for (i in config.warmupBars until klines.size) {
            val bar = klines[i]
            if (active != null) {
                val close = closeActiveTrade(active, bar, equity)
                if (close != null) {
                    equity = close.exitEquity
                    trades += close.trade
                    active = null
                    equityCurve += equity
                    continue
                }
            }

            val closedBars = klines.subList(0, i)
            val prediction = engine.predict(closedBars, leverage = 10) ?: continue
            if (prediction.direction == SignalDirection.FLAT) continue

            val entryPrice = nextBarFillPrice(bar.open, prediction.direction)
            val stop = prediction.stopLoss
            val target = prediction.takeProfit
            val perUnitRisk = (entryPrice.toDouble() - stop.toDouble()).let { kotlin.math.abs(it) }.toBigDecimal()
            if (perUnitRisk <= BigDecimal.ZERO) continue

            val riskAmount = equity.multiply(BigDecimal.valueOf(config.riskPerTrade))
            var quantity = riskAmount.divide(perUnitRisk, 8, RoundingMode.DOWN)
                .min(BigDecimal("1000000"))

            if (quantity <= BigDecimal.ZERO) continue
            val entryFee = notional(entryPrice, quantity).multiply(feeFraction())
            if (entryFee >= equity) continue

            equity = equity - entryFee
            active = ActiveTrade(
                direction = prediction.direction,
                signalTime = bar.openTime,
                entryTime = bar.openTime + 1,
                entryPrice = entryPrice,
                stopLoss = stop,
                takeProfit = target,
                quantity = quantity,
                entryFees = entryFee,
            )
            equityCurve += equity
        }

        active?.let {
            val finalBar = klines.last()
            val exitEquity = closeAtEnd(it, finalBar, equity)
            equity = exitEquity.exitEquity
            trades += exitEquity.trade
            equityCurve += equity
        }

        for (j in 1 until equityCurve.size) {
            val previous = equityCurve[j - 1].toDouble()
            if (previous > 0) returns += (equityCurve[j].toDouble() / previous) - 1.0
        }

        val grossWins = trades.filter { it.netPnl > BigDecimal.ZERO }.sumOf { it.netPnl }
        val grossLosses = trades.filter { it.netPnl < BigDecimal.ZERO }.sumOf { it.netPnl }.abs()
        val profitFactor = if (grossLosses == BigDecimal.ZERO) {
            if (grossWins == BigDecimal.ZERO) BigDecimal.ZERO else BigDecimal.valueOf(Double.MAX_VALUE).setScale(8)
        } else grossWins.divide(grossLosses, 8, RoundingMode.HALF_UP)

        val wins = trades.count { it.netPnl > BigDecimal.ZERO }
        val winRate = if (trades.isEmpty()) BigDecimal.ZERO else
            BigDecimal.valueOf(wins * 100.0).divide(BigDecimal.valueOf(trades.size.toLong()), 8, RoundingMode.HALF_UP)

        return BacktestResult(
            initialEquity = initialEquity,
            finalEquity = equity,
            totalReturnPct = equity.subtract(initialEquity).divide(initialEquity, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")),
            maxDrawdownPct = calculateMaxDrawdown(equityCurve),
            winRatePct = winRate,
            profitFactor = profitFactor,
            sharpeRatio = calculateSharpe(returns),
            sortinoRatio = calculateSortino(returns),
            totalTrades = trades.size,
            trades = trades,
        )
    }

    private data class ActiveTrade(
        val direction: SignalDirection,
        val signalTime: Long,
        val entryTime: Long,
        val entryPrice: BigDecimal,
        val stopLoss: BigDecimal,
        val takeProfit: BigDecimal,
        val quantity: BigDecimal,
        val entryFees: BigDecimal,
    )

    private data class ClosedTrade(val trade: BacktestTrade, val exitEquity: BigDecimal)

    private fun nextBarFillPrice(close: Double, direction: SignalDirection): BigDecimal {
        val slip = config.feeModel.slippageBps / 10_000.0 * config.slippageFactor
        val multiplier = when (direction) {
            SignalDirection.LONG -> 1.0 + slip
            SignalDirection.SHORT -> 1.0 - slip
            SignalDirection.FLAT -> 1.0
        }
        return BigDecimal.valueOf(close * multiplier).setScale(8, RoundingMode.HALF_UP)
    }

    private fun closeActiveTrade(active: ActiveTrade, bar: Kline, currentEquity: BigDecimal): ClosedTrade? {
        val exitPrice: BigDecimal
        val reason: ExitReason
        val stopReached = when (active.direction) {
            SignalDirection.LONG -> bar.low <= active.stopLoss.toDouble()
            SignalDirection.SHORT -> bar.high >= active.stopLoss.toDouble()
            SignalDirection.FLAT -> false
        }
        val targetReached = when (active.direction) {
            SignalDirection.LONG -> bar.high >= active.takeProfit.toDouble()
            SignalDirection.SHORT -> bar.low <= active.takeProfit.toDouble()
            SignalDirection.FLAT -> false
        }

        if (stopReached) {
            exitPrice = active.stopLoss
            reason = ExitReason.STOP
        } else if (targetReached) {
            exitPrice = active.takeProfit
            reason = ExitReason.TARGET
        } else {
            return null
        }
        return close(active, bar.openTime, exitPrice, reason, currentEquity)
    }

    private fun closeAtEnd(active: ActiveTrade, bar: Kline, equity: BigDecimal): ClosedTrade {
        val exitPrice = BigDecimal.valueOf(bar.close).setScale(8, RoundingMode.HALF_UP)
        return close(active, bar.openTime, exitPrice, ExitReason.END_OF_DATA, equity)
    }

    private fun close(active: ActiveTrade, exitTime: Long, exitPrice: BigDecimal, reason: ExitReason, equityBeforeExit: BigDecimal): ClosedTrade {
        val grossPnl = when (active.direction) {
            SignalDirection.LONG -> exitPrice.subtract(active.entryPrice).multiply(active.quantity)
            SignalDirection.SHORT -> active.entryPrice.subtract(exitPrice).multiply(active.quantity)
            SignalDirection.FLAT -> BigDecimal.ZERO
        }
        val exitFee = notional(exitPrice, active.quantity).multiply(feeFraction())
        val netPnl = grossPnl.subtract(active.entryFees).subtract(exitFee)
        val trade = BacktestTrade(
            direction = active.direction,
            signalTime = active.signalTime,
            entryTime = active.entryTime,
            exitTime = exitTime,
            entryPrice = active.entryPrice,
            exitPrice = exitPrice,
            stopLoss = active.stopLoss,
            takeProfit = active.takeProfit,
            quantity = active.quantity,
            grossPnl = grossPnl,
            fees = active.entryFees + exitFee,
            netPnl = netPnl,
            returnPct = netPnl.divide(notional(active.entryPrice, active.quantity), 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")),
            exitReason = reason,
        )
        val exitEquityChange = grossPnl.subtract(exitFee)
        return ClosedTrade(trade, equityBeforeExit + exitEquityChange)
    }

    fun feeFraction(): BigDecimal =
        BigDecimal.valueOf((config.feeModel.takerBps + config.feeModel.slippageBps) / 10_000.0)

    private fun notional(price: BigDecimal, quantity: BigDecimal): BigDecimal =
        price.multiply(quantity)

    private fun calculateMaxDrawdown(curve: List<BigDecimal>): BigDecimal {
        if (curve.isEmpty()) return BigDecimal.ZERO
        var peak = curve.first()
        var maxDd = BigDecimal.ZERO
        curve.forEach {
            if (it > peak) peak = it
            val dd = peak.subtract(it).divide(peak, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            if (dd > maxDd) maxDd = dd
        }
        return maxDd
    }

    private fun calculateSharpe(returns: List<Double>): BigDecimal {
        if (returns.size < 2) return BigDecimal.ZERO
        val mean = returns.average()
        val std = sqrt(returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1))
        if (std == 0.0) return BigDecimal.ZERO
        return BigDecimal.valueOf(mean / std * sqrt(365.0)).setScale(8, RoundingMode.HALF_UP)
    }

    private fun calculateSortino(returns: List<Double>): BigDecimal {
        if (returns.size < 2) return BigDecimal.ZERO
        val mean = returns.average()
        val downside = returns.filter { it < 0 }
        if (downside.isEmpty()) return BigDecimal.ZERO
        val downsideDeviation = sqrt(downside.sumOf { it * it } / downside.size)
        if (downsideDeviation == 0.0) return BigDecimal.ZERO
        return BigDecimal.valueOf(mean / downsideDeviation * sqrt(365.0)).setScale(8, RoundingMode.HALF_UP)
    }
}
