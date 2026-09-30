package com.duluin.ftth.monitoring

import com.duluin.ftth.cpe.adapter.outbound.acs.observedParameterTime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class WarehouseDiscoveryITParameterTime {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `fresh Inform cannot certify cached fields with old or absent timestamps`() {
        val fresh = Instant.parse("2026-09-16T12:00:00Z")
        val old = Instant.parse("2026-09-16T10:00:00Z")
        val retained = mapper.readTree("""{"_lastInform":"$fresh","SSID":{"_value":"A-private","_timestamp":"$old"}}""")
        assertThat(observedParameterTime(retained, fresh)).isEqualTo(old)
        assertThat(observedParameterTime(mapper.readTree("""{"SSID":{"_value":"A-private"}}"""), fresh)).isNull()
        assertThat(observedParameterTime(mapper.readTree("""{"SSID":{"_value":"A-private","_timestamp":"invalid"}}"""), fresh)).isNull()
    }

    @Test
    fun `all exposed values require their own timestamp and earliest evidence wins`() {
        val first = Instant.parse("2026-09-16T11:00:00Z")
        val second = first.plusSeconds(10)
        val values = mapper.readTree("""{"SSID":{"_value":"B-private","_timestamp":"$first"},"Host":{"IP":{"_value":"192.0.2.2","_timestamp":"$second"}}}""")
        assertThat(observedParameterTime(values)).isEqualTo(first)
    }
}
