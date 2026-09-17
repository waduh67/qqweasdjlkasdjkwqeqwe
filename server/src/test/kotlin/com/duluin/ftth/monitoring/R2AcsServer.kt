package com.duluin.ftth.monitoring

import com.duluin.ftth.cpe.adapter.outbound.acs.GenieAcsGateway
import com.sun.net.httpserver.HttpServer
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class R2AcsServer : AutoCloseable {
    val document = AtomicReference("[]")
    val files = AtomicReference("[]")
    val pending = AtomicReference("[]")
    val status = AtomicInteger(200)
    val posts = AtomicInteger()
    val onGet = AtomicReference<(String) -> Unit>({})
    val onPost = AtomicReference<(String) -> Unit>({})
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val task = exchange.requestMethod == "POST"
            val body = if (task) {
                val count = posts.incrementAndGet()
                onPost.get().invoke(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
                "{\"_id\":\"${count.toString(16).padStart(24, '0')}\",\"device\":\"${path.split('/')[2]}\",\"name\":\"setParameterValues\"}"
            } else {
                onGet.get().invoke(path)
                when { path.startsWith("/files") -> files.get(); path.startsWith("/tasks") -> pending.get(); path.startsWith("/faults") -> "[]"; else -> document.get() }
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (task) status.get() else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val client = RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build()
    val gateway = GenieAcsGateway(client, client, "", "http://owned.test/download", "http://owned.test/upload", 1024,
        Duration.ofMillis(150), Duration.ofMillis(5))
    override fun close() = server.stop(0)
}
