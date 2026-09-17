package com.duluin.ftth.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class WarehouseDiscoveryITR2Generation {
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
                val params = jacksonObjectMapper().readTree(body).path("parameterValues")
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
}
