package com.apols.model

import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads klines from CSV. Expected column order (Binance format):
 * openTime, open, high, low, close, volume, ... (extra columns ignored).
 *
 * Header lines and invalid rows are skipped and counted. Output is sorted ascending by
 * openTime with duplicate timestamps removed. Microsecond timestamps are converted to ms.
 */
object CsvKlineLoader {
    data class Result(val klines: List<Kline>, val skippedRows: Int, val duplicatesDropped: Int)

    fun load(path: Path): Result {
        val rows = ArrayList<Kline>()
        var skipped = 0
        Files.newBufferedReader(path).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val f = line.split(',')
                if (f.size < 6) { skipped++; continue }
                try {
                    var t = f[0].trim().toLong()
                    if (t > 100_000_000_000_000L) t /= 1000   // microseconds -> milliseconds
                    rows += Kline(
                        t, f[1].trim().toDouble(), f[2].trim().toDouble(),
                        f[3].trim().toDouble(), f[4].trim().toDouble(), f[5].trim().toDouble()
                    )
                } catch (e: IllegalArgumentException) {   // includes NumberFormatException
                    skipped++
                }
            }
        }
        rows.sortBy { it.openTime }
        val unique = rows.distinctBy { it.openTime }
        return Result(unique, skipped, rows.size - unique.size)
    }
}
