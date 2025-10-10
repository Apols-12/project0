package com.apols


import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.css.*
import java.io.File

fun Application.configureTemplating() {
    routing {
        staticFiles("/image", File("./image.jpg") )
        get("/styles.css", {
            hidden = true
        }) {
            call.respondCss {
                body {
                    fontFamily = "Arial, sans-serif"
                    backgroundColor = Color("#f0f2f5")
                    display = Display.flex
                    justifyContent = JustifyContent.center
                    alignItems = Align.center
                    height = 100.vh
                    margin = Margin(0.rem)
                }

                rule(".login-container") {
                    backgroundColor = Color.aliceBlue
                    padding = Padding(2.rem)
                    borderRadius = 8.px
                    width = 300.px
                }

                rule(".form-group") {
                    marginBottom = 1.rem
                }

                rule("label") {
                    display = Display.block
                    marginBottom = 0.5.rem
                    color = Color("#333")
                }
                rule("input[type=\"text\"], input[type=\"password\"]") {
                    width = 200.px
                    padding = Padding(0.5.rem)
                    border = Border(width = 1.px, BorderStyle.solid, Color.black)
                    borderRadius = 4.px
                    boxSizing = BoxSizing.borderBox
                }
                rule("button") {
                    width = 200.px
                    padding = Padding(0.75.rem)
                    backgroundColor = Color("#007bff")
                    color = Color.white
                    border = Border(width = 2.px, BorderStyle.solid, Color.black)
                    borderRadius = 4.px
                    cursor = Cursor.pointer
                    fontSize = 1.rem
                }
                rule("button:hover") {
                    backgroundColor = Color("#0056b3")
                }
                rule("error") {
                    color = Color.red
                    marginBottom = 1.rem
                }
                rule("h1.page-title") {
                    color = Color.white
                }
            }

        }
        get("/styles1.css", {
            hidden = true
        }) {
            call.respondCss {
                body {
                    fontFamily = "Arial, sans-serif"
                    backgroundColor = Color.tan
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    justifyContent = JustifyContent.center
                    alignItems = Align.center
                    height = 100.vh
                    margin = Margin(0.rem)
                }
                rule(".img") {
                    width = 400.px
                    height = 400.px
                    borderRadius = 50.px
                }
            }
        }
    }
}
suspend inline fun ApplicationCall.respondCss(builder: CssBuilder.() -> Unit) {
   this.respondText(CssBuilder().apply(builder).toString(), ContentType.Text.CSS)
}

