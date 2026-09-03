package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.application.service.RadiusSessionControlRunner
import com.duluin.ftth.bng.application.service.RadiusSubscriberSessionDisconnectConfirmer
import com.duluin.ftth.bng.domain.model.SessionObservation
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.UUID

class SubscriberSessionDisconnectConfirmerTest {
    private val tenantId = UUID.randomUUID()
    private val worker = mock(RadiusSessionControlRunner::class.java)
    private val radius = mock(RadiusAccountingReadPort::class.java)
    private val tenants = mock(TenantApi::class.java)

    @Test
    fun `existing worker dispatches DAE and authoritative RADIUS absence confirms closure`() = TenantContext.runAs(tenantId) {
        `when`(radius.isConfigured()).thenReturn(true)
        `when`(tenants.findById(tenantId)).thenReturn(TenantRef(tenantId, "tenant-a", "Tenant A", TenantStatus.ACTIVE))
        `when`(radius.activeSessions(tenantId, "tenant-a")).thenReturn(listOf(session()), emptyList())
        val confirmer = RadiusSubscriberSessionDisconnectConfirmer(
            worker, radius, tenants, Duration.ofMillis(250), Duration.ofMillis(5),
        )

        val evidence = confirmer.confirm(setOf("fixed-user"), 1)

        verify(worker).run(tenantId)
        assertThat(evidence.activeSessionCount).isZero()
        confirmer.destroy()
    }

    @Test
    fun `bounded timeout preserves still-live evidence`() = TenantContext.runAs(tenantId) {
        `when`(radius.isConfigured()).thenReturn(true)
        `when`(tenants.findById(tenantId)).thenReturn(TenantRef(tenantId, "tenant-a", "Tenant A", TenantStatus.ACTIVE))
        `when`(radius.activeSessions(tenantId, "tenant-a")).thenReturn(listOf(session()))
        val confirmer = RadiusSubscriberSessionDisconnectConfirmer(
            worker, radius, tenants, Duration.ofMillis(30), Duration.ofMillis(5),
        )

        val evidence = confirmer.confirm(setOf("fixed-user"), 1)

        assertThat(evidence.activeSessionCount).isEqualTo(1)
        confirmer.destroy()
    }

    @Test
    fun `worker failure preserves still-live evidence`() = TenantContext.runAs(tenantId) {
        `when`(radius.isConfigured()).thenReturn(true)
        `when`(tenants.findById(tenantId)).thenReturn(TenantRef(tenantId, "tenant-a", "Tenant A", TenantStatus.ACTIVE))
        doThrow(IllegalStateException("DAE unavailable")).`when`(worker).run(tenantId)
        val confirmer = RadiusSubscriberSessionDisconnectConfirmer(
            worker, radius, tenants, Duration.ofMillis(30), Duration.ofMillis(5),
        )

        val evidence = confirmer.confirm(setOf("fixed-user"), 1)

        assertThat(evidence.activeSessionCount).isEqualTo(1)
        confirmer.destroy()
    }

    private fun session() = SessionObservation(
        "fixed-user", true, "203.0.113.9", "100.64.0.1", "session-1", null, 60, 0, 0,
    )
}
