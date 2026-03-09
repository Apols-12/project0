plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "com.apols"
version = "0.0.1"

application {
    mainClass = "com.apols.ApplicationKt"
}

repositories {
    mavenCentral()
    maven { setUrl("https://jitpack.io")}
    maven("https://packages.jetbrains.team/maven/p/kds/kotlin-ds-maven")
}

dependencies {
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.html)
    implementation(libs.ktor.server.core)
    implementation(libs.kotlin.css)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.expose.core)
    implementation(libs.expose.jdbc)
    implementation(libs.h2)
    implementation("io.github.smiley4:ktor-openapi:5.0.0")
//    And this is for the swagger ui
    implementation("io.github.smiley4:ktor-swagger-ui:5.0.0")
    implementation("io.github.wuhewuhe:bybit-java-api:1.2.8")
    implementation("org.jetbrains.kotlinx:kotlin-deeplearning-tensorflow:0.5.2")
    implementation("io.ktor:ktor-client-core:3.2.0")
    implementation("io.ktor:ktor-client-cio:3.2.0")
    implementation("io.ktor:ktor-server-rate-limit:3.2.0")
    implementation("org.jetbrains.exposed:exposed-java-time:1.0.0-beta-5")
    implementation("io.ktor:ktor-client-content-negotiation:3.2.0")
    implementation("dev.whyoleg.cryptography:cryptography-core:0.5.0")
    implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.5.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}
