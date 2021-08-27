package io.beatmaps.scoresaber

import io.beatmaps.common.db.setupDB
import io.beatmaps.common.setupLogging
import io.ktor.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

val port = System.getenv("LISTEN_PORT")?.toIntOrNull() ?: 3031
val host = System.getenv("LISTEN_HOST") ?: "127.0.0.1"

fun main() {
    setupLogging()
    setupDB()

    embeddedServer(Netty, port = port, host = host, module = Application::scoresaber).start(wait = true)
}

fun Application.scoresaber() {
    startScraper()
}