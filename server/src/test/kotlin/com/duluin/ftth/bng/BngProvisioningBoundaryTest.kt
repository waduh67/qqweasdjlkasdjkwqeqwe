package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.BngActionService
import com.duluin.ftth.bng.application.service.BngProvisioningApiService
import com.duluin.ftth.bng.application.service.DisconnectConfirmation
import com.duluin.ftth.bng.application.service.SubscriberAccessLifecycle
import com.duluin.ftth.bng.application.service.SubscriberSessionDisconnectConfirmer
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.NasReachability
import com.duluin.ftth.bng.domain.model.NasVendor
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.provisioning.adapter.outbound.bng.BngSubscriberAccessAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.util.UUID

class BngProvisioningBoundaryTest {
    private val now = Instant.parse("2026-09-03T08:00:00Z")
    private val tenantId = UUID.randomUUID()
    private val subscriptionId = UUID.randomUUID()
    private val planId = UUID.randomUUID()
    private val access = SubscriberAccess.create(
        tenantId, subscriptionId, UUID.randomUUID(), "fixed-user", "pppoe-secret-never-crosses",
        planId, UUID.randomUUID(), AccessStatus.ACTIVE,
    )

    @Test
    fun `findAccess exposes capability and session facts without subscriber secret`() {
        val fixture = fixture(confirmation = DisconnectConfirmation(1, now))

        val result = requireNotNull(fixture.service.findAccess(subscriptionId))

        assertThat(result.activeSessionCount).isEqualTo(1)
        assertThat(result.pppoeTerminationCapable).isTrue()
        assertThat(result.serviceClass).isEqualTo("Residential")
        assertThat(result.javaClass.declaredFields.map { it.name }).doesNotContain("secret", "credential", "password")
        assertThat(result.toString()).doesNotContain("pppoe-secret-never-crosses")
    }

    @Test
    fun `unsupported NAS vendor is not reported PPPoE capable merely because enabled`() {
        val fixture = fixture(nasVendor = NasVendor.OTHER, confirmation = DisconnectConfirmation(1, now))

        assertThat(fixture.service.findNas(access.nasId!!)!!.pppoeTerminationCapable).isFalse()
        assertThat(fixture.service.findAccess(subscriptionId)!!.pppoeTerminationCapable).isFalse()
    }

    @Test
    fun `lifecycle methods delegate to BNG ownership`() {
        val fixture = fixture(confirmation = DisconnectConfirmation(0, now))

        fixture.service.activate(subscriptionId)
        fixture.service.isolate(subscriptionId)
        fixture.service.terminate(subscriptionId)

        verify(fixture.lifecycle).onActivated(subscriptionId)
        verify(fixture.lifecycle).onIsolated(subscriptionId)
        verify(fixture.lifecycle).onTerminated(subscriptionId)
    }

    @Test
    fun `adapter returns confirmed zero only after BNG confirmer observes session closure`() {
        val fixture = fixture(confirmation = DisconnectConfirmation(0, now.plusSeconds(1)))
        val adapter = BngSubscriberAccessAdapter(fixture.service)

        val evidence = adapter.disconnectActiveSessions(subscriptionId)

        verify(fixture.actions).enqueueDisconnect(access, null, null)
        verify(fixture.confirmer).confirm(setOf(access.username), 1)
        assertThat(evidence.activeSessionCount).isZero()
        assertThat(evidence.observedAt).isEqualTo(now.plusSeconds(1))
    }

    @Test
    fun `adapter keeps deletion blocked when confirmation times out still live`() {
        val fixture = fixture(confirmation = DisconnectConfirmation(1, now.plusSeconds(5)))
        val adapter = BngSubscriberAccessAdapter(fixture.service)

        val evidence = adapter.disconnectActiveSessions(subscriptionId)

        assertThat(evidence.activeSessionCount).isEqualTo(1)
        assertThat(evidence.observedAt).isEqualTo(now.plusSeconds(5))
    }

    private fun fixture(
        nasVendor: NasVendor = NasVendor.MIKROTIK,
        confirmation: DisconnectConfirmation,
    ): Fixture {
        val accesses = mock(SubscriberAccessRepository::class.java)
        val sessions = mock(RadiusSessionRepository::class.java)
        val nas = mock(NasRepository::class.java)
        val catalog = mock(CatalogApi::class.java)
        val lifecycle = mock(SubscriberAccessLifecycle::class.java)
        val actions = mock(BngActionService::class.java)
        val confirmer = mock(SubscriberSessionDisconnectConfirmer::class.java)
        `when`(accesses.findBySubscriptionId(subscriptionId)).thenReturn(listOf(access))
        `when`(sessions.findBySubscriberAccessId(access.id)).thenReturn(liveSession())
        `when`(nas.findById(access.nasId!!)).thenReturn(nas(nasVendor))
        `when`(catalog.findPlanNetwork(planId)).thenReturn(plan())
        `when`(actions.enqueueDisconnect(access, null, null)).thenReturn(true)
        `when`(confirmer.confirm(setOf(access.username), 1)).thenReturn(confirmation)
        return Fixture(
            BngProvisioningApiService(accesses, sessions, nas, catalog, lifecycle, actions, confirmer, Duration.ofMinutes(3)),
            lifecycle,
            actions,
            confirmer,
        )
    }

    private fun nas(vendor: NasVendor) = Nas.create(
        tenantId, "BRAS", vendor, "203.0.113.9", null, "coa-secret", null,
        reachability = NasReachability.DIRECT,
    )

    private fun liveSession() = RadiusSession.start(
        tenantId, access.id, subscriptionId, access.customerId, access.username, true, access.nasId,
        "203.0.113.9", "100.64.0.1", "session-1", null, 60, now,
    )

    private fun plan() = PlanNetworkRef(
        planId, "Residential", 100, 20, "20M/100M", 1, false, null, null, null, null,
        setOf(AuthType.PPPOE.name),
    )

    private data class Fixture(
        val service: BngProvisioningApiService,
        val lifecycle: SubscriberAccessLifecycle,
        val actions: BngActionService,
        val confirmer: SubscriberSessionDisconnectConfirmer,
    )
}
