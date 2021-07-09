package io.beatmaps.beatsaver

import io.beatmaps.common.db.setupDB
import io.beatmaps.common.setupAMQP
import io.ktor.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import pl.jutupe.ktor_rabbitmq.RabbitMQ

fun main() {
    //setupLogging()
    setupDB()

    embeddedServer(Netty, port = 3031, host = "0.0.0.0", module = Application::beatsaver).start(wait = true)
}

fun Application.beatsaver() {
    val mq = install(RabbitMQ) {
        setupAMQP()
    }

    startScraper(mq)
}