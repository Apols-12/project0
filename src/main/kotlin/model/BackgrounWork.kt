package com.apols.model

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.time.Duration.Companion.minutes

class BackgrounWork (private val baseUrl: String, private val client: HttpClient) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = KotlinLogging.logger("back_ground_logs")


    fun start() {
        scope.launch {
            while (isActive) {
                try {
                   val response = client.get("$baseUrl/health")
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