package com.duluin.ftth.monitoring

import com.duluin.ftth.cpe.domain.model.SpeedDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

class WarehouseDiscoveryITReviewDiagnostics {
    @ParameterizedTest
    @ValueSource(strings = ["PING_202", "PING_200", "SPEED_202", "SPEED_200"])
    fun `CPE-2 actual gateway cannot accept a cached completion for a new request`(scenario: String) {
        WarehouseReviewAcsServer().use { acs ->
            val old = Instant.now().minusSeconds(3600)
            acs.taskStatus.set(if (scenario.endsWith("202")) 202 else 200)
            acs.document.set("""[{"_id":"OWNED","_deviceId":{"_SerialNumber":"SERIAL"},"_lastInform":"${Instant.now()}",
                "InternetGatewayDevice":{"IPPingDiagnostics":{
                    "DiagnosticsState":{"_value":"Complete","_timestamp":"$old"},"Host":{"_value":"owned.test","_timestamp":"$old"},
                    "NumberOfRepetitions":{"_value":4,"_timestamp":"$old"},"SuccessCount":{"_value":4,"_timestamp":"$old"},
                    "FailureCount":{"_value":0,"_timestamp":"$old"}},
                "DownloadDiagnostics":{"DiagnosticsState":{"_value":"Complete","_timestamp":"$old"},
                    "DownloadURL":{"_value":"http://owned.test/download","_timestamp":"$old"},
                    "TestBytesReceived":{"_value":1000000,"_timestamp":"$old"},
                    "BOMTime":{"_value":"${old.minusSeconds(1)}","_timestamp":"$old"},"EOMTime":{"_value":"$old","_timestamp":"$old"}}}}]""")
            if (scenario.startsWith("PING")) assertThat(acs.gateway.runPing("OWNED", "owned.test", 4).complete).isFalse()
            else assertThat(acs.gateway.runSpeedTest("OWNED", SpeedDirection.DOWNLOAD).complete).isFalse()
            assertThat(acs.tasks.get()).isEqualTo(1)
        }
    }
}
