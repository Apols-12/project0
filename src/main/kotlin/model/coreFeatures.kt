package com.apols.model

import com.alibaba.fastjson.JSON
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.jetbrains.kotlinx.dl.api.inference.TensorFlowInferenceModel
import java.io.File
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.use

private val RECV_WINDOW = "5000"
// Base URL - use testnet for testing
private val BYBIT_MAINNET = "https://api.bybit.com"
private val BYBIT_TESTNET = "https://api-demo.bybit.com"

class CoreFeature(private val httpClient: HttpClient) {
    private val logger = KotlinLogging.logger("Place_Order")

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
    data class BybitOrderResponse(
        val retCode: Int,
        val retMsg: String,
        val result: OrderResult? = null,
        val retExtInfo: Map<String, String> = emptyMap(),
        val time: Long = 0
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
    private suspend fun hasOpenPosition(
        apiKey: String,
        secret: String,
        symbol: String,
        category: String = "linear",
        baseUrl: String
    ): Boolean {
        val timestamp = System.currentTimeMillis().toString()
        val queryString = "category=$category&symbol=$symbol"
        val signature = generatePostSign( jsonBody = queryString, timestamp = timestamp, apiKey = apiKey, secret = secret) // No request body for GET

        val response = httpClient.get("$baseUrl/v5/position/list") {
            parameter("category", category)
            parameter("symbol", symbol)
            headers.append("X-BAPI-SIGN", signature)
            headers.append("X-BAPI-API-KEY", apiKey)
            headers.append("X-BAPI-TIMESTAMP", timestamp)
            headers.append("X-BAPI-RECV-WINDOW", RECV_WINDOW)
        }

        logger.info("Check open position................................................................")

        val result = json.decodeFromString<BybitResponse<PositionListResult>>(response.bodyAsText())
        println(result)

        if (result.retCode != 0) {
            throw Exception("Failed to fetch positions: ${result.retMsg}")
        }
        // Check if any position has size > 0 (ignoring precision, treat > 0.000001 as open)
        return result.result.list.any { (it.size.toDoubleOrNull() ?: 0.0) > 0.000001 }
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

        if (hasOpenPosition(apiKey = apiKey, secret = secret, symbol = symbol, category = category, baseUrl = url)) {

            logger.info("There are/is an open position....>...>...>...>...>...>...>...>...>...>...>...>...>")
            logger.info("Close and open new position......>....>...>...>...>...>...>...>...>...>...>...>...>")
            val response1 = authenticatedOrder("$url/v5/order/create", orderRequest)
            val response2 = authenticatedOrder("$url/v5/order/create", orderRequest)
            if (response1.retCode != 0) {
                throw Exception("Order failed: ${response1.retMsg}")
            }
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

    //This is how to load a KotlinDl model to make prediction
    fun predict(data: FloatArray): Int {
        var prediction: Int
        TensorFlowInferenceModel.load(File("src/main/resources/scalper_x4"))
            .use {
                it.reshape(1, 9)
                prediction = it.predict(data)
            }
        return prediction
    }
}
