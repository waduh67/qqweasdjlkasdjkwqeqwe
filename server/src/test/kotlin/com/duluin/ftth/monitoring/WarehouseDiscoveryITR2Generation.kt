package com.duluin.ftth.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class WarehouseDiscoveryITR2Generation {
    @Test
    fun `retained future download completion cannot satisfy identical requests`() {
        R2AcsServer().use { server ->
            val future = Instant.now().plusSeconds(120)
            server.document.set("""[{"_id":"DEVICE","_deviceId":{"_SerialNumber":"SERIAL"},"_lastInform":"${Instant.now()}",
                "InternetGatewayDevice":{"DownloadDiagnostics":{"DiagnosticsState":{"_value":"Complete","_timestamp":"$future"},
                "DownloadURL":{"_value":"http://owned.test/download","_timestamp":"$future"},"TestBytesReceived":{"_value":1000000,"_timestamp":"$future"},
                "BOMTime":{"_value":"$future","_timestamp":"$future"},"EOMTime":{"_value":"${future.plusSeconds(1)}","_timestamp":"$future"}}}}]""")
            repeat(2) { assertThat(server.gateway.runSpeedTest("DEVICE", com.duluin.ftth.cpe.domain.model.SpeedDirection.DOWNLOAD).complete).isFalse() }
        }
    }

    private fun device(time: Instant, inform: String?, host: String = "target.invalid", state: String = "Complete"): String {
        val informField = inform?.let { ",\"_lastInform\":\"$it\"" }.orEmpty()
        return """[{"_id":"DEVICE","_deviceId":{"_SerialNumber":"SERIAL"}$informField,"InternetGatewayDevice":{"IPPingDiagnostics":{
            "DiagnosticsState":{"_value":"$state","_timestamp":"$time"},"Host":{"_value":"$host","_timestamp":"$time"},
            "NumberOfRepetitions":{"_value":4,"_timestamp":"$time"},"SuccessCount":{"_value":4,"_timestamp":"$time"},
            "FailureCount":{"_value":0,"_timestamp":"$time"},"AverageResponseTime":{"_value":12,"_timestamp":"$time"},
            "MinimumResponseTime":{"_value":10,"_timestamp":"$time"},"MaximumResponseTime":{"_value":14,"_timestamp":"$time"}}}}]"""
    }

    @ParameterizedTest
    @ValueSource(strings = ["STALE_INFORM", "MISSING_INFORM", "PRIOR_QUEUED"])
    fun `CPE-R2-1 repeated request cannot accept a retained future generation`(mode: String) {
        R2AcsServer().use { server ->
            val future = Instant.now().plusSeconds(120)
            server.document.set(device(future, if (mode == "MISSING_INFORM") null else Instant.now().minusSeconds(600).toString()))
            if (mode == "PRIOR_QUEUED") {
                server.status.set(202)
                assertThat(server.gateway.runPing("DEVICE", "target.invalid", 4).complete).isFalse()
                server.status.set(200)
            }
            repeat(2) { assertThat(server.gateway.runPing("DEVICE", "target.invalid", 4).complete).isFalse() }
        }
    }

    @Test
    fun `a genuinely new diagnostic generation remains successful`() {
        R2AcsServer().use { server ->
            val before = Instant.now().minusSeconds(2)
            server.document.set(device(before, before.toString()))
            server.onPost.set { body ->
                val task = jacksonObjectMapper().readTree(body)
                if (task.path("name").asString() != "setParameterValues") return@set
                val params = task.path("parameterValues")
                val host = params.firstOrNull { it[0].asString().endsWith(".Host") }?.get(1)?.asString() ?: "target.invalid"
                val requested = params.any { it[0].asString().endsWith(".DiagnosticsState") && it[1].asString() == "Requested" }
                val now = Instant.now()
                server.document.set(device(now, now.toString(), host, if (requested) "Complete" else "None"))
            }
            val result = server.gateway.runPing("DEVICE", "target.invalid", 4)
            assertThat(result.complete).isTrue()
            assertThat(result.successCount).isEqualTo(4)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["QUEUED", "TIMED_OUT"])
    fun `an older asynchronous completion during a later request is not the later result`(mode: String) {
        R2AcsServer().use { server ->
            val initial = Instant.now().minusSeconds(2)
            server.document.set(device(initial, initial.toString()))
            var requestedCount = 0
            var lateCompletion = ""
            server.onPost.set { body ->
                val task = jacksonObjectMapper().readTree(body)
                if (task.path("name").asString() != "setParameterValues") return@set
                val params = task.path("parameterValues")
                val host = params.firstOrNull { it[0].asString().endsWith(".Host") }?.get(1)?.asString() ?: "target.invalid"
                val requested = params.any { it[0].asString().endsWith(".DiagnosticsState") }
                val now = Instant.now()
                server.status.set(200)
                if (!requested) server.document.set(device(now, now.toString(), host, "None")) else {
                    requestedCount++
                    when (requestedCount) {
                        1 -> {
                            server.status.set(if (mode == "QUEUED") 202 else 200)
                            server.document.set(device(now, now.toString(), host, "Requested"))
                            if (mode == "QUEUED") server.pending.set("""[{"_id":"${server.posts.get().toString(16).padStart(24, '0')}","device":"DEVICE"}]""")
                        }
                        2 -> server.document.set(lateCompletion)
                        else -> server.document.set(device(now, now.toString(), host))
                    }
                }
            }
            assertThat(server.gateway.runPing("DEVICE", "target.invalid", 4).complete).isFalse()
            server.onGet.set { path ->
                if (path.startsWith("/tasks")) {
                    val now = Instant.now()
                    lateCompletion = device(now.plusSeconds(120), now.toString())
                    server.document.set(lateCompletion)
                    server.pending.set("[]")
                    server.onGet.set {}
                }
            }
            assertThat(server.gateway.runPing("DEVICE", "target.invalid", 4).complete).isFalse()
            val recovered = server.gateway.runPing("DEVICE", "target.invalid", 4)
            assertThat(recovered.complete).isTrue()
            assertThat(requestedCount).isEqualTo(3)
        }
    }
}
