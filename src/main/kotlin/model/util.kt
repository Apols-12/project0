package com.apols.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.collections.average
import kotlin.collections.get
import kotlin.div
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.text.toLongOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.times

@Serializable
data class BybitKlineResponse(
    val retCode: Int,
    val retMsg: String,
    val result: KlineResult
)
@Serializable
data class KlineResult(
    val symbol: String,
    val category: String,
    val list: List<List<Double>>
)

data class Kline(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

@Serializable
data class BotConfig(
    val category: String = "linear",
    val botName: String,
    val symbol: String,
    val qty: String,
    val leverage: Int,
    val tpPercent: Double,
    val slPercent: Double,

    val apiKey: String,
    val secretKey: String,
    val longPeriod: Int,
    val interval: String,
    val shortPeriod: Int,
    val demo: Boolean
)

data class TKlines(
    val close: Double,
    val change: Double,
    val changePtc: Double,
    val volCh: Double,
    val shortEma: Double,
    val longEma: Double,
    val emaLong: Double,
    val emaShort: Double,
    val delta: Double,
    val emaDiff: Double,
    val diffEma: Double
)

fun List<List<Double>>.zScoreNorm(): List<List<Double>> {
    if (isEmpty()) return emptyList()
    val features = this[0].size
    require(all {it.size == features}) {"All inner lists must have the same size"}

    val columns = (0 until features).map { col -> map { row -> row[col] } }
    val colState = columns.map { column ->
        val mean = column.average()
        val std = sqrt(column.map { (it - mean).pow(2) }.average())
        mean to std
    }
    return map { row ->
        row.mapIndexed { col, value ->
            val (mean, std) = colState[col]
            if (std == 0.0) 0.0 else (value - mean ) / std
        }
    }
}

class NetworkService(private val client: HttpClient) {

    fun format(time: Long): String {
        val pattern = "yyyy-MM-dd HH:mm:ss"
        val date = Date(time)
        val formater = SimpleDateFormat(pattern, Locale.getDefault())
        return formater.format(date)
    }

    @OptIn(ExperimentalTime::class)
    suspend fun getKline(
        symbol: String,
        baseUrl: String,
        category: String = "linear",
        interval: String,
        limit: Int
    ): List<Kline> {

        // this is the actual request to the server
        val klineResponse = client.get(baseUrl) {
                contentType(ContentType.Application.Json)
                parameter("category", category)
                parameter("symbol", symbol)
                parameter("interval", interval)
                parameter("limit", limit)
                parameter("end", Clock.System.now().toEpochMilliseconds())
            }


        // Get information about the rate limit and make sure it doesn't get banned
        val rateLimitRemaining = klineResponse.headers["X-Bapi-Limit-Status"]?.toLongOrNull() ?: 1
        val rateLimitReset = klineResponse.headers["X-Bapi-Limit-Reset-Timestamp"]?.toLongOrNull()?: 1
        if (rateLimitRemaining < 3) {
            val waitTime = (rateLimitReset*1000) - System.currentTimeMillis()
            if (waitTime > 0) {
                println("Approaching rate limit. Waiting ${waitTime}ms")
                delay(waitTime.coerceAtLeast(1000).milliseconds)
            }
        }
        // parse it to a format the program can manipulate
        val kline = klineResponse.body<BybitKlineResponse>()
        if (kline.retCode != 0) throw  Exception("API error: ${kline.retMsg}")
        val result =  kline.result.list.map { item ->
            Kline(
                time = format(item[0].toLong()),
                open = item[1],
                high = item[2],
                low = item[3],
                close = item[4],
                volume = item[5]
            )
        }
        return result.sortedBy { it.time }.distinct()
    }
}

class Processor(private val klines: List<Kline>) {
    private val closes get() = klines.map { it.close }

    private fun change(): List<Double> {
        return klines.mapIndexed { index, kline ->
            val prevClose = if (index > 0) klines[index - 1].close else kline.close
            klines[index].close - prevClose
        }
    }

    private fun chPtc(): List<Double> {
        return klines.mapIndexed { index, kline ->
            val prevClose = if (index > 0) klines[index - 1].close else kline.close
            val closeChPtc = ((kline.close - prevClose) / prevClose) * 100
            closeChPtc
        }
    }

    private fun volChange(): List<Double> {
        return klines.mapIndexed { index, kline ->
            val prevVol = if (index > 0) klines[index - 1].volume else kline.volume
            val volCh = if (index == 0) 0.0 else (kline.volume - prevVol)
            volCh
        }
    }

    private fun delta(): List<Double> {
        return List(klines.size) { index ->
            val fluctuation = (klines[index].close - klines[index].open) / (klines[index].high - klines[index].low)
            fluctuation
        }
    }

    private fun calculateEMA(data: List<Double>, period: Int): List<Double> {
        if (data.isEmpty()) return emptyList()
        val ema = mutableListOf<Double>()
        val k = 2.0 / (period + 1)
        var previousEMA = data.take(period).average()
        data.forEachIndexed { index, close ->
            when {
                index < period - 1 -> ema.add(0.0) // Not enough data
                index == period -> {
                    ema.add(previousEMA)
                }
                else -> {
                    val currentEMA = (close - previousEMA) * k + previousEMA
                    ema.add(currentEMA)
                    previousEMA = currentEMA
                }
            }
        }
        return ema
    }

    fun enhanceKline(longPeriod: Int, shortPeriod: Int): List<TKlines> {

        val change = change()
        val changePtc = chPtc()
        val volCh = volChange()
        val delta = delta()
        val emaLong = calculateEMA(change, longPeriod)
        val emaShort = calculateEMA(change, shortPeriod)
        val longEma = calculateEMA(closes, longPeriod)
        val shortEma = calculateEMA(closes, shortPeriod)

        return klines.mapIndexed { index, kline ->
            TKlines(
                close = kline.close,
                change = change[index],
                changePtc = changePtc[index],
                volCh = volCh[index],
                delta = delta[index],
                emaShort = emaShort[index],
                shortEma = shortEma[index],
                longEma = longEma[index],
                emaLong = emaLong[index],
                emaDiff = shortEma[index] - longEma[index],
                diffEma = emaShort[index] - emaLong[index]
            )
        }
    }

    fun processed(data: List<TKlines>): List<List<Double>> {
        return data.map { listOf(it.change, it.changePtc, it.delta, it.emaDiff) }
    }

}