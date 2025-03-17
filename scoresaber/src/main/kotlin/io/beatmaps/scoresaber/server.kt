package io.beatmaps.scoresaber

import io.beatmaps.common.db.setupDB
import io.beatmaps.common.amqp.setupAMQP
import io.beatmaps.common.setupLogging
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import pl.jutupe.ktor_rabbitmq.RabbitMQ

val port = System.getenv("LISTEN_PORT_SS")?.toIntOrNull() ?: 3031
val host = System.getenv("LISTEN_HOST_SS") ?: System.getenv("LISTEN_HOST") ?: "127.0.0.1"

fun main() {
    setupLogging()
    setupDB()

    embeddedServer(Netty, port = port, host = host, module = Application::scoresaber).start(wait = true)
}

fun Application.scoresaber() {
    val mq = install(RabbitMQ) {
        setupAMQP(false)
    }

    startScraper(mq)
}
