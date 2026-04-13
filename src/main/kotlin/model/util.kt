package com.apols.model

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.text.toLongOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    val botName: String,
    val symbol: String,
    val qty: String,
    val apiKey: String,
    val secretKey: String,
    val profitRoi: String,
    val lossRoi: String,
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
                delay(waitTime.coerceAtLeast(1000))
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
