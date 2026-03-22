package com.apols.model

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import kotlin.time.Duration.Companion.minutes

class BackgrounWork (private val baseUrl: String) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = KotlinLogging.logger("back_ground_logs")
    private val client = HttpClient(CIO) {
        install(HttpRequestRetry) {
            maxRetries = 5
            retryOnExceptionIf { _, cause ->
                cause is ServerResponseException && cause.response.status == HttpStatusCode.TooManyRequests
            }
            exponentialDelay()
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    this@HttpClient.expectSuccess = true
                }
            )
        }
    }

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                   val response = client.use {
                       it.get("$baseUrl/health")
                   }
                    logger.info("the server is up: ${response.status}")
                    delay(10.minutes)
                } catch (e: Exception) {
                    e.printStackTrace()
                    delay(3.minutes)
                }
            }
        }
    }

    fun stop() {
        client.close()
        scope.cancel()
    }
}