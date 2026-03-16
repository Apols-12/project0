package com.apols.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

class BotManager(private val service: BotService) {
    private val activeBots = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val position =  mutableMapOf<String, Int?>()
    val mutex = Mutex()
    private val logger = KotlinLogging.logger("bot_manager_logs")
    val botStatus get() =  activeBots.mapValues {
        if (it.value.isActive) "Running" else "Stopped"
    }

    fun startBot(config: BotConfig) {
        stopBot(config.botName)
        activeBots[config.botName] = scope.launch {
            while (isActive) {
                mutex.withLock {
                    try {
                        val currentPosition = position[config.botName]
                        val newPosition = service.start(config, currentPosition)
                        if (newPosition != currentPosition) {
                            position[config.botName] = newPosition
                        }
                        delay(300000)
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

    fun stopBot(userId: String) {
        activeBots[userId]?.let {
            it.cancel("User Requested Stop")
            position.remove(userId)
            activeBots.remove(userId)
            logger.info("Bot stop successfully")
            // We can also notify the user after that
        }
    }

//    fun stopAllBots() {
//        scope.cancel("System ShutDown")
//        // We can notify users about these events too
//    }
}