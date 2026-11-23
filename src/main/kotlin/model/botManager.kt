package com.apols.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BotManager(private val service: BotService) {
    private val activeBots = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val mutex = Mutex()
    private val logger = KotlinLogging.logger("bot_manager_logs")
    val botStatus get() =  activeBots.mapValues {
        if (it.value.isActive) "Running" else "Stopped"
    }

    fun getBotsStatus(): Flow<BotStat> {
        return botStatus.map {
            BotStat(it.key, it.value)
        }.asFlow()
    }

    suspend fun startBot(config: BotConfig) {
        mutex.withLock {
            activeBots.remove(config.botName)?.let { oldJob ->
                oldJob.cancel("Replacing bot")
                oldJob.join()
            }
            activeBots[config.botName] = scope.launch {
                while (isActive) {
                    try {
                        service.start(config)
                        delay(30.seconds)
                    } catch (e: CancellationException) {
                        // We will create a function to notify the user about this event
                        logger.info("[${config.botName}] Bot stopped gracefully")
                        throw e
                    } catch (e: Exception) {
                        // Also notify the user about this event
                        logger.warn("And exception happen for bot:${config.botName} with exc: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun stopBot(userId: String) {
        val oldJob = activeBots.remove(userId)
        oldJob?.cancel("User Requested Stop")
        oldJob?.join()
        logger.info("Bot stop successfully")
    }

    suspend fun stopAllBots() {
        val jobs = activeBots.values.toList()
        jobs.forEach { it.cancel("Stopping all bots") }
        jobs.joinAll()
        activeBots.clear()
    }
}