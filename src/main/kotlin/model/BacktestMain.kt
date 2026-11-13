package com.apols.model

import java.nio.file.Path
import kotlin.math.max

/**
 * Usage:
 *   gradle run --args="data/BTCUSDT-1h.csv --oos 0.3 --taker 10 --slip 2 --risk 0.01 --hold 48"
 *
 * Prints the full-period result, plus in-sample vs out-of-sample results when --oos > 0.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: <klines.csv> [--oos 0.3] [--taker 10] [--slip 2] [--risk 0.01] [--hold 48] [--equity 10000]")
        return
    }
    val opts = args.drop(1).chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val oos = opts["oos"]?.toDouble() ?: 0.3
    val fees = FeeModel(opts["taker"]?.toDouble() ?: 10.0, opts["slip"]?.toDouble() ?: 2.0)
    val cfg = BacktestConfig(
        initialEquity = opts["equity"]?.toDouble() ?: 10_000.0,
        riskPerTrade = opts["risk"]?.toDouble() ?: 0.01,
        maxHoldBars = opts["hold"]?.toInt() ?: 48
    )
    require(oos in 0.0..0.9) { "--oos must be between 0 and 0.9" }

    val loaded = CsvKlineLoader.load(Path.of(args[0]))
    val klines = loaded.klines
    println("Loaded ${klines.size} klines (skipped ${loaded.skippedRows} rows, dropped ${loaded.duplicatesDropped} duplicates)")
    require(klines.size > cfg.warmupBars + 10) { "not enough data" }

    val bt = Backtester(SignalEngine(fees = fees), fees, cfg)
    println(bt.run(klines).report("FULL PERIOD"))

    if (oos > 0.0) {
        val split = (klines.size * (1.0 - oos)).toInt()
        val inSample = klines.subList(0, split)
        val outSample = klines.subList(max(0, split - (cfg.warmupBars - 1)), klines.size)
        println()
        println(bt.run(inSample).report("IN-SAMPLE (first ${((1 - oos) * 100).toInt()}%)"))
        println()
        println(bt.run(outSample).report("OUT-OF-SAMPLE (last ${(oos * 100).toInt()}%)"))
    }
}
