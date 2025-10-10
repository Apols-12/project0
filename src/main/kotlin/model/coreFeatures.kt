package com.apols.model

import com.bybit.api.client.config.BybitApiConfig
import com.bybit.api.client.service.BybitApiClientFactory
import mu.KotlinLogging
import org.jetbrains.kotlinx.dl.api.inference.TensorFlowInferenceModel
import java.io.File
import kotlin.use

class CoreFeature(private val klines: List<Kline>) {
    private val logger = KotlinLogging.logger("Place_Order")
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
        return data.map { listOf(it.change, it.shortEma, it.longEma, it.delta, it.emaDiff) }
    }

    //This is how to load a KotlinDl model to make prediction
    fun predict(data: FloatArray): Int {
        var prediction: Int
        TensorFlowInferenceModel.load(File("./Titan1.0"))
            .use {
                it.reshape(20,5)
                prediction = it.predict(data)
            }
        return prediction
    }

    // This is the function to place an order
    // We can create a notify function to notify a user if and operation has been performed in his account
    fun placeOrder(
        side: String,
        symbol: String,
        quantity: String,
        apiKey: String,
        secret: String,
        demo: Boolean
    ) {
        if (side == "Neutral") {
            logger.info("No need to rush")
        } else {
            val order = mapOf(
                "category" to "linear",
                "symbol" to symbol,
                "side" to side,
                "orderType" to "Market",
                "qty" to quantity
            )
            val url = if(demo) BybitApiConfig.DEMO_TRADING_DOMAIN else BybitApiConfig.MAINNET_DOMAIN
            val client = BybitApiClientFactory.newInstance(apiKey, secret, url, 9000)
                .newAsyncTradeRestClient()
            client.createOrder(order){logger.info(it.toString())}
        }
    }
}
