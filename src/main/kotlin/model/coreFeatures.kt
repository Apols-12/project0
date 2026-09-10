package com.apols.model

import com.alibaba.fastjson.JSON
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.core.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.kotlinx.dl.api.inference.TensorFlowInferenceModel
import java.io.File
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.let
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

private val RECV_WINDOW = "5000"
// Base URL - use testnet for testing
private val BYBIT_MAINNET = "https://api.bybit.com"
private val BYBIT_TESTNET = "https://api-demo.bybit.com"

class CoreFeature(private val httpClient: HttpClient) {
    private val logger = KotlinLogging.logger("Place_Order")
    val model = TensorFlowInferenceModel.load(File("models/scalper_max"))

    @Serializable
    data class BybitResponse<T>(
        val retCode: Int,
        val retMsg: String,
        val result: T,
        val time: Long
    )


    @Serializable
    data class TickerResult(
        val result: Result
    )


    @Serializable
    data class Result(
        val category: String,
        val list: List<TickerData>
    )

    @Serializable
    data class TickerData(
        val symbol: String,
        val lastPrice: String,
        val markPrice: String
    )

    @Serializable
    data class PositionListResult(
        val list: List<PositionData>
    )

    @Serializable
    data class PositionData(
        val symbol: String,
        val side: String,          // "Buy" or "Sell"
        val size: String,
        val positionIdx: Int,
        val unrealisedPnl: String,
        val leverage: String
        // ... add other fields as needed
    )

    @Serializable
    data class SetLeverageRequest(
        val apiKey: String,
        val secret: String,
        val category: String,
        val symbol: String,
        val buyLeverage: String,
        val sellLeverage: String
    )

    @Serializable
    data class PlaceOrderRequest(
        val apiKey: String,
        val secret: String,
        val category: String,
        val symbol: String,
        val side: String,          // "Buy" or "Sell"
        val orderType: String,     // "Market" or "Limit"
        val qty: String,
        val price: String? = null,
        val timeInForce: String? = null,
        val positionIdx: Int = 0,  // 0 = one‑way mode
        val takeProfit: String? = null,
        val stopLoss: String? = null,
        val tpTriggerBy: String? = null,
        val slTriggerBy: String? = null
    )


    @Serializable
    data class OrderResult(
        val orderId: String? = null,
        val orderLinkId: String? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }


    /**
     * Convert a byte array to a hexadecimal string.
     * Exactly replicates the Java implementation.
     */
    private fun bytesToHex(hash: ByteArray): String {
        val hexString = StringBuilder()
        for (b in hash) {
            val hex = Integer.toHexString(0xff and b.toInt())
            if (hex.length == 1) hexString.append('0')
            hexString.append(hex)
        }
        return hexString.toString()
    }


    /**
     * Place an order on Bybit V5 API.
     *
     * @param order The order request parameters
     * @param useTestnet Whether to use testnet (default: false)
     * @return The API response containing order details
     * @throws Exception if the order placement fails
     */

    private suspend fun authenticatedOrder(endpoint: String, body: PlaceOrderRequest): BybitResponse<OrderResult> {
        val params = mutableMapOf<String, Any?>(
            "category" to body.category,
            "symbol" to body.symbol,
            "side" to body.side,
            "orderType" to body.orderType,
            "qty" to body.qty,
            "price" to body.price,
            "timeForce" to body.timeInForce,
            "positionIdx" to body.positionIdx,
            "takeProfit" to body.takeProfit,
            "stopLoss" to body.stopLoss,
            "tpTriggerBy" to body.tpTriggerBy,
            "slTriggerBy" to body.slTriggerBy
        )

        delay(5.seconds)
        val timestamp = System.currentTimeMillis().toString()

        val bodyJson = JSON.toJSONString(params)
        val signature = generatePostSign(jsonBody = bodyJson,  timestamp = timestamp, apiKey = body.apiKey, secret = body.secret)

        logger.info("Open new order>>>>>>>>>>>>>>>>><<<<<<<>>>>>>>>>>><<<<<<<<<>>>>>>>>>>>>>>>>>>")
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            headers.append("X-BAPI-SIGN", signature)
            headers.append("X-BAPI-API-KEY", body.apiKey)
            headers.append("X-BAPI-TIMESTAMP", timestamp)
            headers.append("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            setBody(bodyJson)
        }

        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun authenticatedLeverage(endpoint: String, body: SetLeverageRequest): BybitResponse<Unit> {

        val params = mutableMapOf<String, Any?>(
            "category" to body.category,
            "symbol" to body.symbol,
            "buyLeverage" to body.buyLeverage,
            "sellLeverage" to body.sellLeverage
        )

        val timestamp = System.currentTimeMillis().toString()

        val bodyJson = JSON.toJSONString(params)
        val signature = generatePostSign(jsonBody = bodyJson,  timestamp = timestamp, apiKey = body.apiKey, secret = body.secret)

        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            headers.append("X-BAPI-SIGN", signature)
            headers.append("X-BAPI-API-KEY", body.apiKey)
            headers.append("X-BAPI-TIMESTAMP", timestamp)
            headers.append("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            setBody(bodyJson)
        }

        return json.decodeFromString(response.bodyAsText())
    }

    /**
     * Generate signature for POST requests (matches your Java logic).
     */
    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    private fun generatePostSign(jsonBody: String, timestamp: String, apiKey: String, secret: String): String {
        val payload = timestamp + apiKey + RECV_WINDOW + jsonBody

        val sha256HMAC = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        sha256HMAC.init(secretKey)

        return bytesToHex(sha256HMAC.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    private suspend fun getCurrentPrice(symbol: String, category: String = "linear", baseUrl: String): Double {
        val response = httpClient.get("$baseUrl/v5/market/tickers") {
            parameter("category", category)
            parameter("symbol", symbol)
        }

        logger.info("Getting current price fo $symbol>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
        val result = json.decodeFromString<TickerResult>(response.bodyAsText())

        return result.result.list.firstOrNull()?.lastPrice?.toDoubleOrNull()
            ?: throw Exception("Could not fetch price for $symbol")
    }

    // ------------------------------------------------------------
    // 2. Set Leverage
    // ------------------------------------------------------------
    private suspend fun setLeverage(
        apiKey: String,
        secret: String,
        symbol: String,
        leverage: Int,
        category: String = "linear",
        baseUrl: String
    ): Boolean {
        val request = SetLeverageRequest(
            apiKey = apiKey,
            secret = secret,
            category = category,
            symbol = symbol,
            buyLeverage = leverage.toString(),
            sellLeverage = leverage.toString()
        )

        logger.info("Setting new leverage of $leverage for $category>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")

        val response = authenticatedLeverage("$baseUrl/v5/position/set-leverage", request)

        logger.info("Setting leverage: $response")
        return response.retCode == 0 || response.retCode == 110043
    }

    /**
     * Checks if there is an open position for the given symbol.
     * @param symbol Trading pair, e.g., "BTCUSDT"
     * @param category "linear" (USDT perpetual) or "inverse" (coin perpetual)
     * @return true if any position has size > 0
     */
    suspend fun getOpenPositions(
        apiKey: String,
        secret: String,
        symbol: String,
        category: String = "linear",
        useDemo: Boolean
    ): List<PositionData> {
        val url = if (useDemo) BYBIT_TESTNET else BYBIT_MAINNET
        val timestamp = System.currentTimeMillis().toString()
        val queryString = "category=$category&symbol=$symbol"
        val signature = generatePostSign( jsonBody = queryString, timestamp = timestamp, apiKey = apiKey, secret = secret) // No request body for GET

        val response = httpClient.get("$url/v5/position/list") {
            parameter("category", category)
            parameter("symbol", symbol)
            headers.append("X-BAPI-SIGN", signature)
            headers.append("X-BAPI-API-KEY", apiKey)
            headers.append("X-BAPI-TIMESTAMP", timestamp)
            headers.append("X-BAPI-RECV-WINDOW", RECV_WINDOW)
        }

        logger.info("Get open positions...............................................................")

        val result = json.decodeFromString<BybitResponse<PositionListResult>>(response.bodyAsText())

        if (result.retCode != 0) {
            throw Exception("Failed to fetch positions: ${result.retMsg}")
        }
        // Check if any position has size > 0 (ignoring precision, treat > 0.000001 as open)
        return result.result.list
    }

    suspend fun hasOpenPosition(
        apiKey: String,
        secret: String,
        symbol: String,
        category: String = "linear",
        useDemo: Boolean
    ): Boolean {
        val url = if (useDemo) BYBIT_TESTNET else BYBIT_MAINNET
        val timestamp = System.currentTimeMillis().toString()
        val queryString = "category=$category&symbol=$symbol"
        val signature = generatePostSign( jsonBody = queryString, timestamp = timestamp, apiKey = apiKey, secret = secret) // No request body for GET

        val response = httpClient.get("$url/v5/position/list") {
            parameter("category", category)
            parameter("symbol", symbol)
            headers.append("X-BAPI-SIGN", signature)
            headers.append("X-BAPI-API-KEY", apiKey)
            headers.append("X-BAPI-TIMESTAMP", timestamp)
            headers.append("X-BAPI-RECV-WINDOW", RECV_WINDOW)
        }

        logger.info("Check open position................................................................")

        val result = json.decodeFromString<BybitResponse<PositionListResult>>(response.bodyAsText())

        if (result.retCode != 0) {
            throw Exception("Failed to fetch positions: ${result.retMsg}")
        }
        // Check if any position has size > 0 (ignoring precision, treat > 0.000001 as open)
        return result.result.list.map { it.size }.any { it > "0.00001" }
    }

    suspend fun closeOpenPositions(
        apiKey: String,
        secret: String,
        symbol: String,
        category: String,
        useDemo: Boolean
    ) {
        val url = if (useDemo) BYBIT_TESTNET else BYBIT_MAINNET
        val positions = getOpenPositions(apiKey = apiKey, secret = secret, symbol = symbol, category = category, useDemo = useDemo)

        for (pos in positions) {
            val closeSide = if (pos.side.equals("Buy", ignoreCase = true)) "Sell" else "Buy"
            val params = mutableMapOf<String, Any?>(
                "category" to category,
                "symbol" to symbol,
                "side" to closeSide,
                "orderType" to "Market",
                "qty" to pos.size,
                "positionIdx" to pos.positionIdx,

            )

            val timestamp = System.currentTimeMillis().toString()

            val bodyJson = JSON.toJSONString(params)
            val signature = generatePostSign( jsonBody = bodyJson, timestamp = timestamp, apiKey = apiKey, secret = secret) // No request body for GET

            val response = httpClient.post("$url/v5/order/create") {
                contentType(ContentType.Application.Json)
                headers.append("X-BAPI-SIGN", signature)
                headers.append("X-BAPI-API-KEY", apiKey)
                headers.append("X-BAPI-TIMESTAMP", timestamp)
                headers.append("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                setBody(bodyJson)
            }.body<BybitResponse<OrderResult>>()

            logger.info("Close order placed>>>>>>>>>>>>>>>>>>>>>>>>>>>>${response.retCode}")
        }

    }
// ------------------------------------------------------------
// 3. Place Order with TP/SL (Percentages)                    ||
// ------------------------------------------------------------
    suspend fun placeOrderWithTPSL(
        apiKey: String,
        secret: String,
        symbol: String,
        side: String,               // "Buy" or "Sell"
        quantity: String,
        leverage: Int,
        takeProfitPercent: Double,   // e.g., 5.0 for 5%
        stopLossPercent: Double,     // e.g., 2.0 for 2%
        category: String = "linear",
        useDemo: Boolean
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val url = if (useDemo) BYBIT_TESTNET else BYBIT_MAINNET
        // 1. Set leverage first
        val leverageOk = setLeverage(apiKey = apiKey, secret = secret, symbol = symbol, category = category, leverage = leverage, baseUrl = url)
        require(leverageOk) { "Failed to set leverage" }

        // 2. Get current price
        val currentPrice = getCurrentPrice(symbol = symbol, category = category, baseUrl = url)

        // 3. Calculate TP/SL prices based on percentages
        val tpPrice = if (side.equals("Buy", ignoreCase = true)) {
            currentPrice * (1 + takeProfitPercent / leverage)
        } else {
            currentPrice * (1 - takeProfitPercent / leverage)
        }

        val slPrice = if (side.equals("Buy", ignoreCase = true)) {
            currentPrice * (1 - stopLossPercent / leverage)
        } else {
            currentPrice * (1 + stopLossPercent / leverage)
        }

        // 4. Place market order with TP/SL attached
        val orderRequest = PlaceOrderRequest(
            apiKey = apiKey,
            secret = secret,
            category = category,
            symbol = symbol,
            side = side,
            orderType = "Market",
            qty = quantity,
            positionIdx = 0,
            takeProfit = tpPrice.toString(),
            stopLoss = slPrice.toString(),
            tpTriggerBy = "MarkPrice",
            slTriggerBy = "MarkPrice"
        )

        val side = hasOpenPosition(apiKey = apiKey, secret = secret, symbol = symbol, category = category, useDemo = useDemo)
        if (side) {
            logger.info("There are/is an open position....>...>...>...>...>...>...>...>...>...>...>...>...>")
            closeOpenPositions(apiKey = apiKey, secret = secret, symbol = symbol, category = category, useDemo = useDemo)
            val response2 = scope.async {  authenticatedOrder("$url/v5/order/create", orderRequest) }.await()
            if (response2.retCode != 0) {
                throw Exception("Order failed: ${response2.retMsg}")
            }

        } else {
            logger.info("There are no open position>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
            logger.info("Opening a new position>>>>>>>>>>>>>>>>>>>>>>>>>>>........>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
            val response = authenticatedOrder("$url/v5/order/create", orderRequest)
            if (response.retCode != 0) {
                throw Exception("Order failed: ${response.retMsg}")
            }
        }
    }


    data class FeatureRow(
        val timestamp: Long,
        val features: DoubleArray,
        val label: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as FeatureRow

            if (timestamp != other.timestamp) return false
            if (label != other.label) return false
            if (!features.contentEquals(other.features)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + label
            result = 31 * result + features.contentHashCode()
            return result
        }
    }

    // ---------- Technical indicators ----------
    fun computeSMA(prices: DoubleArray, period: Int): DoubleArray {
        val sma = DoubleArray(prices.size) { Double.NaN }
        for (i in period - 1 until prices.size) {
            var sum = 0.0
            for (j in i - period + 1..i) sum += prices[j]
            sma[i] = sum / period
        }
        return sma
    }

    fun computeEMA(prices: DoubleArray, period: Int): DoubleArray {
        val ema = DoubleArray(prices.size) { Double.NaN }
        if (prices.size < period) return ema
        val multiplier = 2.0 / (period + 1)
        ema[period - 1] = prices.take(period).average()
        for (i in period until prices.size) {
            ema[i] = (prices[i] - ema[i - 1]) * multiplier + ema[i - 1]
        }
        return ema
    }

    fun computeRSI(prices: DoubleArray, period: Int = 14): DoubleArray {
        val rsi = DoubleArray(prices.size) { Double.NaN }
        if (prices.size <= period) return rsi

        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val diff = prices[i] - prices[i - 1]
            if (diff >= 0) avgGain += diff else avgLoss -= diff
        }
        avgGain /= period
        avgLoss /= period
        rsi[period] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgGain / avgLoss)

        for (i in period + 1 until prices.size) {
            val diff = prices[i] - prices[i - 1]
            val gain = max(diff, 0.0)
            val loss = max(-diff, 0.0)
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            rsi[i] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1 + avgGain / avgLoss)
        }
        return rsi
    }

    // ---------- Feature engineering ----------
    fun createFeatures(candles: List<Kline>): List<FeatureRow> {
        val closes = candles.map { it.close }.toDoubleArray()
        val volumes = candles.map { it.volume }.toDoubleArray()
        val highs = candles.map { it.high }.toDoubleArray()
        val lows = candles.map { it.low }.toDoubleArray()

        val sma5 = computeSMA(closes, 5)
        val sma20 = computeSMA(closes, 20)
        val ema4 = computeEMA(closes, 4)
        val ema5 = computeEMA(closes, 5)
        val ema6 = computeEMA(closes, 6)
        val ema7 = computeEMA(closes, 7)
        val ema8 = computeEMA(closes, 8)
        val ema9 = computeEMA(closes, 9)
        val ema10 = computeEMA(closes, 10)
        val ema11 = computeEMA(closes, 11)
        val ema12 = computeEMA(closes, 12)
        val ema13 = computeEMA(closes, 13)
        val ema14 = computeEMA(closes, 14)
        val ema15 = computeEMA(closes, 15)
        val rsi = computeRSI(closes, 14)

        val futureSteps = 3
        val threshold = 0.5

        val featureRows = mutableListOf<FeatureRow>()
        val start = maxOf(26, 14, 20, 5) // slowest indicator

        for (i in start until candles.size - futureSteps) {
            val features = doubleArrayOf(
                (closes[i] - closes[i - 1]) / closes[i - 1],
                (closes[i] - closes[i - 3]) / closes[i - 3],
                (closes[i] - closes[i - 7]) / closes[i - 7],
                (closes[i] - closes[i - 14]) / closes[i - 14],
                (closes[i] - sma5[i]) / closes[i],
                (closes[i] - sma20[i]) / closes[i],
                (closes[i] - ema4[i]) / closes[i],
                (closes[i] - ema5[i]) / closes[i],
                (closes[i] - ema6[i]) / closes[i],
                (closes[i] - ema7[i]) / closes[i],
                (closes[i] - ema8[i]) / closes[i],
                (closes[i] - ema9[i]) / closes[i],
                (closes[i] - ema10[i]) / closes[i],
                (closes[i] - ema11[i]) / closes[i],
                (closes[i] - ema12[i]) / closes[i],
                (closes[i] - ema13[i]) / closes[i],
                (closes[i] - ema14[i]) / closes[i],
                (rsi[i] - 50.0) / 50.0,
                (volumes[i] - volumes[i - 1]) / volumes[i - 1],
                (volumes[i] - volumes[i - 5]) / volumes[i - 5],
                (highs[i] - lows[i]) / closes[i]
            )

//        val label = if (ema4[i] - ema14[i] > threshold) 1 else 0
            val diff = ((ema4[i] - ema9[i]) * 0.5 + (ema4[i] - ema10[i]) * 0.5 + (ema4[i] - ema12[i]) * 0.5 + (ema4[i] - ema13[i]) * 0.5 + (ema4[i] - ema14[i]) * 0.5) / 5
            val label = if (diff > threshold) 1 else 0
            featureRows.add(FeatureRow(candles[i].time, features, label))
        }
        return featureRows
    }

    // ---------- Prepare sequences for Conv1D ----------
    data class SequenceData(
        val x: Array<FloatArray>, // [batch, timeSteps, features]
        val y: FloatArray               // [batch]
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as SequenceData

            if (!x.contentDeepEquals(other.x)) return false
            if (!y.contentEquals(other.y)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = x.contentDeepHashCode()
            result = 31 * result + y.contentHashCode()
            return result
        }
    }


    // ---------- Normalization (Z-score per feature) ----------
    class StandardScaler {
        var mean: FloatArray? = null
        var std: FloatArray? = null

        fun fit(featureMatrix: List<FloatArray>) {
            val nFeatures = featureMatrix[0].size
            mean = FloatArray(nFeatures)
            std = FloatArray(nFeatures)

            for (j in 0 until nFeatures) {
                val col = featureMatrix.map { it[j] }
                mean!![j] = col.average().toFloat()
                std!![j] = sqrt(col.map { (it - mean!![j]).pow(2) }.average().toFloat())
                if (std!![j] == 0.0f) std!![j] = 1.0f
            }
        }

        fun transform(seq: Array<FloatArray>): Array<FloatArray> {
            require(mean != null && std != null) { "Scaler not fitted" }
            return seq.map { row ->
                FloatArray(row.size) { j ->
                    ((row[j] - mean!![j]) / std!![j])
                }
            }.toTypedArray()
        }
    }

    private fun processData(data: List<Kline>): FloatArray {
        val featureRows = createFeatures(data)

        val dataX = featureRows.asSequence().map { it.features.map { t -> t.toFloat() } }.windowed(10, 1).map { it.flatten() }.map { it.toFloatArray() }
            .toList().toTypedArray()

        val scaler = StandardScaler()
        scaler.fit(dataX.toList())

        // Transform for prediction
        return scaler.transform(dataX).toList().takeLast(1).single()
    }

    private fun predict(data: FloatArray): Int {
        model.let {
            it.reshape(10L, 21L)
            return it.predict(data)
        }
    }

    fun prediction(klines: List<Kline>): Prediction {
        val data = processData(klines)
        val prediction =  predict(data)
        return when(prediction) {
             1 -> Prediction.Buy()
             0 -> Prediction.Sell()
            else -> Prediction.Neutral
        }
    }
}
