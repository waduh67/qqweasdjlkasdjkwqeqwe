package com.duluin.ftth.monitoring

import com.duluin.ftth.cpe.adapter.outbound.acs.GenieAcsGateway
import com.sun.net.httpserver.HttpServer
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class WarehouseReviewAcsServer : AutoCloseable {
    val document = AtomicReference("[]")
    val taskStatus = AtomicInteger(202)
    val tasks = AtomicInteger()
    val onTask = AtomicReference<(String) -> Unit>({})
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/devices") { exchange ->
            val body = if (exchange.requestMethod == "POST") {
                tasks.incrementAndGet()
                onTask.get().invoke(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                "{\"_id\":\"owned-task\"}"
            } else document.get()
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (exchange.requestMethod == "POST") taskStatus.get() else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val client = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
    val gateway = GenieAcsGateway(client, client, "", "http://owned.test/download", "http://owned.test/upload", 1024,
        Duration.ofMillis(100), Duration.ofMillis(5))
    override fun close() = server.stop(0)
}
