package com.duluin.ftth.incident

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.incident.application.service.IncidentCorrelationService
import com.duluin.ftth.monitoring.AlarmImpact
import com.duluin.ftth.monitoring.MonitoringApi
import com.duluin.ftth.network.NetworkApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.UUID

class IncidentCorrelationBoundaryTest {
    private val monitoring = Mockito.mock(MonitoringApi::class.java)
    private val service = IncidentCorrelationService(monitoring, Mockito.mock(NetworkApi::class.java), Mockito.mock(CustomerApi::class.java))

    @Test
    fun `customer session alerts do not prevent supported network incidents`() {
        val olt = UUID.randomUUID()
        Mockito.`when`(monitoring.activeImpacts()).thenReturn(listOf(
            AlarmImpact("CUSTOMER", UUID.randomUUID(), "WARNING", "PPPOE_DOWN", "Budi"),
            AlarmImpact("OLT", olt, "CRITICAL", "OLT_UNREACHABLE", "OLT Lab"),
        ))
        val incident = service.correlate().single()
        assertThat(incident.rootType).isEqualTo("OLT")
        assertThat(incident.rootId).isEqualTo(olt)
        assertThat(incident.alarmCount).isEqualTo(1)
    }

    @Test
    fun `unsupported alert roots are not persisted as network incidents`() {
        Mockito.`when`(monitoring.activeImpacts()).thenReturn(listOf(
            AlarmImpact("CUSTOMER", UUID.randomUUID(), "WARNING", "PPPOE_DOWN", "Budi"),
        ))
        assertThat(service.correlate()).isEmpty()
    }
}
