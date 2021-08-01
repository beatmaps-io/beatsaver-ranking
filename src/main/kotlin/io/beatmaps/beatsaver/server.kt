package io.beatmaps.beatsaver

import io.beatmaps.common.db.setupDB
import io.beatmaps.common.setupAMQP
import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import pl.jutupe.ktor_rabbitmq.RabbitMQ

val port = System.getenv("LISTEN_PORT")?.toIntOrNull() ?: 3031

fun main() {
    //setupLogging()
    setupDB()

    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::beatsaver).start(wait = true)
}

fun Application.beatsaver() {
    val mq = install(RabbitMQ) {
        setupAMQP()
    }

    startScraper(mq)
}