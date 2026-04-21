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
import java.time.Instant
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale
import kotlin.collections.average
import kotlin.collections.get
import kotlin.collections.windowed
import kotlin.div
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.text.toLongOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.times
import kotlin.unaryMinus

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


data class KlineFeatures(
    val time: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val returnPct: Double, // (close - prevClose)/prevClose * 100
    val change: Double,
    val changePct: Double,
    val logReturn: Double,          // ln(close/prevClose)
    val delta: Double,         // rolling std of returns (window)
    val smaDiff: Double, // simple moving average of close
    val smaLong: Double,
    val smaShort: Double,
    val emaDiff: Double, // exponential moving average
    val diffEma: Double,
    val emaLong: Double,
    val emaShort: Double,
    val rsiDiff: Double, // Relative Strength Index (14 periods)
    val rsiLong: Double,
    val rsiShort: Double,
    val bbUpper: Double,            // Bollinger Band upper
    val bbLower: Double,            // Bollinger Band lower
    val volumeSma: Double, // volume moving average
    val macdLine: Double,
    val signalLine: Double,
    val histogram: Double,
    val volumeChangePct: Double,    // (volume - prevVolume)/prevVolume * 100
    val highLowRatio: Double,       // (high - low)/close
    val closeOpenRatio: Double      // (close - open)/open
)

fun List<Kline>.withReturns(): List<Pair<Kline, Double>> =
    zipWithNext { prev, curr ->
        curr to ((curr.close - prev.close) / prev.close) * 100
    }.map { (kline, ret) -> kline to ret }

fun List<Kline>.sma(window: Int): List<Double> {
    return indices.map { i ->
        if (i < window - 1) Double.NaN
        else subList(i - window + 1, i + 1).sumOf { it.close } / window
    }
}

fun List<Double>.proEma(period: Int): List<Double> {
    if (isEmpty()) return emptyList()
    val ema = mutableListOf<Double>()
    val k = 2.0 / (period + 1)
    var previousEMA = take(period).average()
    forEachIndexed { index, close ->
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


fun List<Kline>.macd(fast: Int = 12, slow: Int = 26, signal: Int = 3): Triple<List<Double>, List<Double>, List<Double>> {
    val closes = map { it.close }

    val emaFast = closes.proEma(fast)
    val emaSlow = closes.proEma(slow)

    val macdLine = emaFast.zip(emaSlow) {f, s -> f - s}
    val signalLine = macdLine.proEma(signal)
    val histogram = macdLine.zip(signalLine) {f, s -> f - s}

    return Triple(macdLine, signalLine, histogram)
}

fun List<Kline>.rsi(period: Int = 14): List<Double> {
    if (size < period + 1) return List(size) { Double.NaN }
    val gains = mutableListOf<Double>()
    val losses = mutableListOf<Double>()
    for (i in 1 until size) {
        val diff = this[i].close - this[i - 1].close
        gains.add(maxOf(diff, 0.0))
        losses.add(maxOf(-diff, 0.0))
    }
    // first avg gain/loss
    var avgGain = gains.take(period).average()
    var avgLoss = losses.take(period).average()
    val rsiValues = MutableList(size) { Double.NaN }
    // first RSI after initial period
    val firstRs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
    rsiValues[period] = 100.0 - (100.0 / (1.0 + firstRs))
    // subsequent RSI using Wilder's smoothing
    for (i in period + 1 until size) {
        avgGain = (avgGain * (period - 1) + gains[i - 1]) / period
        avgLoss = (avgLoss * (period - 1) + losses[i - 1]) / period
        val rs = if (avgLoss == 0.0) 100.0 else avgGain / avgLoss
        rsiValues[i] = 100.0 - (100.0 / (1.0 + rs))
    }
    return rsiValues
}

fun List<Kline>.bollingerBands(window: Int = 20, k: Double = 2.0): Triple<List<Double>, List<Double>, List<Double>> {
    val smaValues = this.sma(window)
    val stdDevs = indices.map { i ->
        if (i < window - 1) Double.NaN
        else {
            val slice = subList(i - window + 1, i + 1)
            val mean = slice.sumOf { it.close } / window
            val variance = slice.sumOf { (it.close - mean).pow(2) } / window
            sqrt(variance)
        }
    }
    val upper = smaValues.zip(stdDevs) { sma, sd -> if (sd.isNaN()) Double.NaN else sma + k * sd }
    val lower = smaValues.zip(stdDevs) { sma, sd -> if (sd.isNaN()) Double.NaN else sma - k * sd }
    return Triple(smaValues, upper, lower)
}

fun List<Kline>.volumeSma(window: Int): List<Double> {
    return indices.map { i ->
        if (i < window - 1) Double.NaN
        else subList(i - window + 1, i + 1).sumOf { it.volume } / window
    }
}

fun List<Kline>.volumeChangePct(): List<Double> {
    return listOf(Double.NaN) + zipWithNext { prev, curr ->
        ((curr.volume - prev.volume) / prev.volume) * 100
    }
}

fun List<Kline>.change(): List<Double> {
    return mapIndexed { index, kline ->
        val prevClose = if (index > 0) this[index - 1].close else kline.close
        this[index].close - prevClose
    }
}

fun List<Kline>.chPtc(): List<Double> {
    return mapIndexed { index, kline ->
        val prevClose = if (index > 0) this[index - 1].close else kline.close
        val closeChPtc = ((kline.close - prevClose) / prevClose) * 100
        closeChPtc
    }
}

fun List<Kline>.delta(): List<Double> {
    return List(this.size) { index ->
        val fluctuation = ((this[index].close - this[index].open) / (this[index].high - this[index].low))
        fluctuation
    }
}


fun List<Kline>.computeAllFeatures(
    smaLong: Int = 26,
    smaShort: Int = 12,
    emaLong: Int = 26,
    emaShort: Int = 12,
    rsiLong: Int = 26,
    rsiShort: Int = 12,
    volWindow: Int = 14,
    bbWindow: Int = 20
): List<KlineFeatures> {
    val closes = map { it.close }
    val returns = withReturns().associate { it.first to it.second }
    val logReturns = zipWithNext { prev, curr ->
        ln(curr.close / prev.close)*100
    }.let { listOf(Double.NaN) + it }
    val smaLongList = sma(smaLong)
    val smaShortList = sma(smaShort)
    val emaLongList = closes.proEma(emaLong)
    val emaShortList = closes.proEma(emaShort)
    val rsiLongList = rsi(rsiLong)
    val rsiShortList = rsi(rsiShort)
    val (_, bbUpperList, bbLowerList) = bollingerBands(bbWindow)
    val volSmaList = volumeSma(volWindow)
    val volChangeList = volumeChangePct()
    val (macdLine, signalLine, histogram) = macd()
    val changes = change()
    val changePct = chPtc()
    val delta = delta()
    val shortEmas = changes.proEma(smaShort)
    val longEmas = changes.proEma(emaLong)
    return indices.map { i ->
        val k = this[i]
        KlineFeatures(
            time = k.time,
            open = k.open,
            high = k.high,
            low = k.low,
            close = k.close,
            volume = k.volume,
            returnPct = returns[k] ?: Double.NaN,
            change = changes[i],
            changePct = changePct[i],
            logReturn = logReturns[i],
            delta = delta[i], // can compute rolling std later
            smaDiff = smaShortList[i] - smaLongList[i],
            smaLong = smaLongList[i],
            smaShort = smaShortList[i],
            emaDiff = emaShortList[i] - emaLongList[i],
            emaLong = emaLongList[i],
            emaShort = emaShortList[i],
            rsiDiff = rsiShortList[i] - rsiLongList[i],
            rsiLong = rsiLongList[i],
            rsiShort = rsiShortList[i],
            diffEma = shortEmas[i] - longEmas[i],
            bbUpper = bbUpperList[i],
            bbLower = bbLowerList[i],
            volumeSma = volSmaList[i],
            macdLine = macdLine[i],
            signalLine = signalLine[i],
            histogram = histogram[i],
            volumeChangePct = volChangeList[i],
            highLowRatio = if (k.close != 0.0) ((k.high - k.low) / k.close)*100 else Double.NaN,
            closeOpenRatio = if (k.open != 0.0) ((k.close - k.open) / k.open)*100 else Double.NaN
        )
    }
}

class Processor(private val klines: List<Kline>) {

    fun processed(data: List<TKlines>): List<List<Double>> {
        return data.map { listOf(it.change, it.changePtc, it.delta, it.emaDiff) }
    }

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

    private fun volChPtc(): List<Double> {
        return klines.mapIndexed { index, kline ->
            val prevVolume = if (index > 0) klines[index - 1].volume else kline.volume
            val volChangePtc = if (index == 0) 0.0 else ((kline.volume - prevVolume) / prevVolume) * 100
            volChangePtc
        }
    }

    private fun delta(): List<Double> {
        return List(klines.size) { index->
            val fluctuation =  ((klines[index].close - klines[index].open)/(klines[index].high - klines[index].low))
            fluctuation
        }
    }

    fun emaDiff(period: Int): List<Double> {
        val change =  klines.mapIndexed { index, kline ->
            val close = if (index > 0) klines[index].close else kline.close
            close
        }
        return List(period - 1){0.0} + change.windowed(period).map { it.average() }
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

    fun calculateRSI(prices: List<Double>, period: Int = 14): List<Double> {
        // Need at least period + 1 prices to compute the first RSI
        if (prices.size < period + 1) return List(prices.size) { 0.0 }

        // Compute price changes, gains, and losses
        val changes = (1 until prices.size).map { i -> prices[i] - prices[i - 1] }
        val gains = changes.map { max(it, 0.0) }
        val losses = changes.map { max(-it, 0.0) }

        // Initial averages (simple average over the first 'period' changes)
        var avgGain = gains.take(period).average()
        var avgLoss = losses.take(period).average()

        val rsi = MutableList(prices.size) { 0.0}

        // First RSI value (at index = period)
        rsi[period] = if (avgLoss == 0.0) 100.0 else {
            val rs = avgGain / avgLoss
            100.0 - (100.0 / (1.0 + rs))
        }

        // Update averages and compute subsequent RSI values
        for (i in period until changes.size) {
            // Wilder's smoothing: newAvg = (prevAvg * (period-1) + current) / period
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period

            val priceIndex = i + 1   // because changes[i] ends at prices[i+1]
            if (priceIndex < prices.size) {
                rsi[priceIndex] = if (avgLoss == 0.0) 100.0 else {
                    val rs = avgGain / avgLoss
                    100.0 - (100.0 / (1.0 + rs))
                }
            }
        }

        return rsi
    }


    fun enhanceKline(shortPeriod: Int, longPeriod: Int): List<TKlines> {
        val closes = klines.map { it.close }

        val change = change()
        val longEma = calculateEMA(closes, longPeriod)
        val shortEma = calculateEMA(closes, shortPeriod)
        val emaShort = calculateEMA(change, shortPeriod)
        val emaLong = calculateEMA(change, longPeriod)
        val changePtc = chPtc()
        val lonRsi = calculateRSI(closes, 14)
        val shorRsi = calculateRSI(closes, 11)
        val volCh = volChange()
        val delta = delta()
        val volPtc = volChPtc()

        return klines.mapIndexed { index, kline ->
            TKlines(
                close = kline.close,
                change = change[index],
                changePtc = changePtc[index],
                volCh = volCh[index],
                delta = delta[index],
//                 vol_percent = volPtc[index],
                emaDiff =  shortEma[index] - longEma[index],
                longEma = longEma[index],
                shortEma = shortEma[index],
                emaShort = emaShort[index],
                emaLong = emaLong[index],
                diffEma = emaShort[index] - emaLong[index]
            )
        }
    }
}


