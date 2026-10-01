package com.duluin.ftth.cpe.adapter.outbound.acs

import com.duluin.ftth.cpe.application.port.outbound.AcsSettingsResolver
import com.duluin.ftth.cpe.domain.model.AcsConnectionSettings
import com.duluin.ftth.monitoring.R2AcsServer
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import tools.jackson.module.kotlin.jacksonObjectMapper

class RuntimeGenieAcsGatewayTest {
    private class Endpoint : AutoCloseable {
        var status = 200
        var delayMs = 0L
        var body = "[]"
        var onGet: () -> Unit = {}
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request ->
                requests.add(request.requestURI.toString() to request.requestHeaders.getFirst("Authorization"))
                if (request.requestMethod == "GET") onGet()
                if (delayMs > 0) Thread.sleep(delayMs)
                val bytes = body.toByteArray()
                runCatching {
                    request.responseHeaders.set("Content-Type", "application/json")
                    request.sendResponseHeaders(status, bytes.size.toLong())
                    request.responseBody.use { it.write(bytes) }
                }
                request.close()
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }
    private fun settings(url: String, username: String = "api", password: String = "api-secret") =
        AcsConnectionSettings.let { AcsConnectionSettings(it.ENV_VERSION, url, "", null, null, false).updated(url, username, password, "http://cwmp.test:7547") }
    private fun gateway(provider: GenieAcsClientProvider) = RuntimeGenieAcsGateway(provider, "", "http://download.test/file", "http://upload.test/file", 1024, Duration.ofMillis(60), Duration.ofMillis(5))

    @Test
    fun `new operations switch endpoint and authentication immediately with no fallback on failure`() {
        Endpoint().use { a -> Endpoint().use { b ->
            val current = AtomicReference(settings(a.url))
            val provider = GenieAcsClientProvider(AcsSettingsResolver { current.get() }, GenieAcsProperties())
            val runtime = gateway(provider)
            assertThat(runtime.listDevices()).isEmpty()
            assertThat(a.requests.single().second).isEqualTo("Basic " + Base64.getEncoder().encodeToString("api:api-secret".toByteArray()))
            current.set(settings(b.url, "", "retained-secret"))
            assertThat(runtime.listDevices()).isEmpty()
            assertThat(b.requests.single().second).isNull()
            b.status = 401
            b.body = "do-not-expose-this-body"
            assertThat(provider.test(current.get()).error).isEqualTo("Autentikasi API ditolak")
            assertThatThrownBy { runtime.listDevices() }.isInstanceOf(org.springframework.web.client.HttpClientErrorException::class.java)
            assertThat(a.requests).hasSize(1)
            assertThat(provider.test(current.get()).error).doesNotContain(b.url, "retained-secret", b.body)
        } }
    }

    @Test
    fun `health timeout is bounded and TLS failure does not silently downgrade to HTTP`() {
        Endpoint().use { endpoint ->
            endpoint.delayMs = 200
            val current = AtomicReference(settings(endpoint.url))
            val provider = GenieAcsClientProvider(AcsSettingsResolver { current.get() }, GenieAcsProperties(connectTimeout = Duration.ofMillis(50), healthTimeout = Duration.ofMillis(30)))
            assertThat(provider.test(current.get()).reachable).isFalse()
            endpoint.delayMs = 0
            current.set(settings(endpoint.url.replace("http:", "https:")))
            val failed = provider.test(current.get())
            assertThat(failed.reachable).isFalse()
            assertThat(failed.error).doesNotContain("api-secret", endpoint.url)
        }
    }

    @Test
    fun `diagnostic completes on its original endpoint when configuration changes during polling`() {
        R2AcsServer().use { a -> Endpoint().use { b ->
            val current = AtomicReference(settings(a.baseUrl))
            val provider = GenieAcsClientProvider(AcsSettingsResolver { current.get() }, GenieAcsProperties())
            val runtime = RuntimeGenieAcsGateway(provider, "", "http://download.test/file", "http://upload.test/file", 1024, Duration.ofSeconds(2), Duration.ofMillis(5))
            fun device(state: String, time: Instant, host: String = "target.invalid") = """[{"_id":"DEVICE","_deviceId":{"_SerialNumber":"SERIAL"},"_lastInform":"$time","InternetGatewayDevice":{"IPPingDiagnostics":{
                "Host":{"_value":"$host","_timestamp":"$time"},"DiagnosticsState":{"_value":"$state","_timestamp":"$time"},
                "NumberOfRepetitions":{"_value":4,"_timestamp":"$time"},"SuccessCount":{"_value":4,"_timestamp":"$time"},
                "FailureCount":{"_value":0,"_timestamp":"$time"},"AverageResponseTime":{"_value":12,"_timestamp":"$time"}}}}]"""
            a.document.set(device("None", Instant.now().minusSeconds(2)))
            val requested = AtomicReference(false)
            val polls = AtomicInteger()
            a.onPost.set { body ->
                val task = jacksonObjectMapper().readTree(body)
                if (task.path("name").asString() == "setParameterValues") {
                    val start = task.path("parameterValues").any { it[0].asString().endsWith(".DiagnosticsState") && it[1].asString() == "Requested" }
                    val host = task.path("parameterValues").firstOrNull { it[0].asString().endsWith(".Host") }?.get(1)?.asString() ?: "target.invalid"
                    a.document.set(device(if (start) "Requested" else "None", Instant.now(), host))
                    if (start) requested.set(true)
                }
            }
            a.onGet.set { path ->
                if (requested.get() && path.startsWith("/devices")) {
                    current.set(settings(b.url))
                    if (polls.incrementAndGet() >= 3) a.document.set(device("Complete", Instant.now()))
                }
            }
            val result = runtime.runPing("DEVICE", "target.invalid", 4) {}
            assertThat(result.complete).isTrue()
            assertThat(result.successCount).isEqualTo(4)
            assertThat(polls.get()).isGreaterThanOrEqualTo(3)
            assertThat(b.requests).isEmpty()
            runtime.listDevices()
            assertThat(b.requests).hasSize(1)
        } }
    }
}
