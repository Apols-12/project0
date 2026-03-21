package com.apols

import com.apols.dataOp.UserInsert
import com.apols.dataOp.Users
import com.apols.dataOp.insertUser
import com.apols.dataOp.verifyUser
import com.apols.model.BackgrounWork
import com.apols.model.BotConfig
import com.apols.model.BotManager
import com.apols.model.BotService
import com.apols.model.NetworkService
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.engine.*
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.util.hex
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.title
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


fun main() {
    Database.connect("jdbc:h2:./myh2db", driver = "org.h2.Driver")
    transaction {
        SchemaUtils.create(Users)
    }
    insertUser(UserInsert("apollinaire", "Guinabadi2@", "apolsng@gmail.com"))
    val networkService = NetworkService()
    val service = BotService(networkService)
    val botService = BotManager(service)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 5000
    val baseUrl = System.getenv("BASE_URL") ?: "https://server_1apols.com/health"
    val background = BackgrounWork(baseUrl)

    embeddedServer(Netty, port = port, "0.0.0.0") {

        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }

        install(Sessions) {
            cookie<UserSession>("auth_session") {
                cookie.path = "/"
                cookie.maxAgeInSeconds = 24 * 60 * 60 // 24 hours
                transform(
                    SessionTransportTransformerEncrypt(
                        hex("00112233445566778899aabbccddeeff"),
                        hex("6819b57a326945c1968f45236589")
                    )
                )
            }
        }
        install(Authentication) {
            basic {
                validate { credential ->
                    if (verifyUser(credential.name, credential.password)) {
                        UserIdPrincipal(credential.name)
                    } else {
                        null
                    }
                }
            }
        }

        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError )
            }
        }

        background.start()

        module()
        routing {
            authenticate {
                route("/bot") {
                    post("/run", {
                        description = "This endpoint allow you to run a new bot"
                        request {
                            body<BotConfig> {
                                description = "the data for the new user."
                                required = true
                                example("default") {
                                    value = BotConfig(
                                        botName = "477823fasnai",
                                        symbol = "SOLUSDT",
                                        qty = "4",
                                        apiKey = "6Q3j13eRvRHJMiID4U",
                                        secretKey = "OZgqxbGdaQv9cHACFnNXoqF0l1KCT6owtgGV",
                                        longPeriod = 26,
                                        interval = "15",
                                        shortPeriod = 12,
                                        demo = true
                                    )
                                }
                            }
                        }
                        response {
                            HttpStatusCode.OK to {
                                description = "A success response"
                                body<String>()
                            }
                            HttpStatusCode.BadRequest to {
                                description = "A bad request"
                                body<String>()
                            }
                        }

                    })  {
                        try {
                            val botConfig = call.receive<BotConfig>()
                            botService.startBot(botConfig)
                            call.respondText("The bot started successfully", status = HttpStatusCode.OK)
                            log.info("${botConfig.botName} bot started successfully")
                        } catch (e: Exception) {
                            // Notify the user
                            e.printStackTrace()
                            log.info("Unable to start the bot, because of an exception")
                            call.respondText("Unable to start the bot, because of an exception", status = HttpStatusCode.InternalServerError)
                        }
                    }

                    get("/stop{bot-name?}", {
                        description = "Stop the bot"
                        request {
                            queryParameter<String>("bot-name") {
                                description = "The boot id you want to stop"
                                example("default") {
                                    value = "477823fasnai"
                                }
                            }
                        }
                        response {
                            code(HttpStatusCode.OK) {
                                body<String> {
                                    required = true
                                    description = "The text you get back from the server"
                                }
                            }
                            code(HttpStatusCode.NotFound) {
                                description = "Return this if the boot is not found"
                            }
                        }
                    }) {
                        val id = call.parameters["bot-name"]
                        if (botService.botStatus.keys.contains(id)) {
                            botService.stopBot(id!!)
                            call.respondText("Boot stoped successfully", status = HttpStatusCode.OK)
                            log.info("$id bot stoped successfully")
                        } else {
                            call.respondText("An error occur, Or you have no running boot", status = HttpStatusCode.NotFound)
                            log.error("An error occur while stoping the the bot, maybe he has no running bot")
                        }
                    }

                    get("/runningBots", {
                        description = "This endpoint return all the running boots"
                        response {
                            code(HttpStatusCode.OK) {
                                description = "Get this status code for a successful request"
                                body<Map<String, String>> {
                                    required = true
                                    description = "All the running boots"
                                }
                            }
                        }
                    }) {
                        val runningBoots = botService.botStatus
                        call.respond(runningBoots)
                    }
                }
            }

            get("/") {
                call.respondText("The bot api is running, only me can access it", status = HttpStatusCode.OK)
            }

            get("/health") {
                call.respond(HttpStatusCode.OK)
            }
        }
        monitor.subscribe(ApplicationStopping) {
            background.stop()
        }
    }.start(wait = true)
}

fun Application.module() {
    routing {
        install(OpenApi) {
            info {
                title = "The openapi for my simple project"
                description = "This Api allow you to interact with my service"
            }
            schemas {  }
            security {
                securityScheme("MySecurityScheme") {
                    type = AuthType.HTTP
                    scheme = AuthScheme.BASIC
                }
                defaultSecuritySchemeNames("MySecurityScheme")
                defaultUnauthorizedResponse {
                    description = "Username or password is invalid"
                    body<AuthRequired> {
                        description = "Return this for unauthenticated call"
                    }
                }
            }
            server {
                url = "http://localhost:5000"
                description = "Development server"
            }
            server {
                url = "https://project0-efd8.onrender.com"
                description = "production domain"
            }
        }
        route("api.json") {
            openApi()
        }
        route("swagger") {
            swaggerUI("/api.json")
        }
    }
}
class AuthRequired(val message: String)

@Serializable
data class UserSession(val name: String?, val id: String?)