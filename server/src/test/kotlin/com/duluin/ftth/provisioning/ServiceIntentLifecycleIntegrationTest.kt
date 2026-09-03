package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.port.outbound.ServiceSegmentState
import com.duluin.ftth.provisioning.application.port.outbound.ServiceSegmentStatePort
import com.duluin.ftth.provisioning.application.port.outbound.SubscriberAccessLifecyclePort
import com.duluin.ftth.provisioning.application.port.outbound.SubscriptionLifecycleStatusPort
import com.duluin.ftth.provisioning.application.service.ServiceIntentLifecycleCoordinator
import com.duluin.ftth.provisioning.application.service.ProvisioningIntentLifecycleService
import com.duluin.ftth.provisioning.domain.model.IntentStatus
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.ServiceIntentSubjectKind
import com.duluin.ftth.provisioning.domain.model.VlanEncapsulation
import com.duluin.ftth.collector.adapter.SimulatorBngAdapter
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.simulator.network.DeterministicNetworkSimulator
import com.duluin.ftth.simulator.network.SimulatorProfiles
import com.duluin.ftth.simulator.network.SimulatorTerminalState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ServiceIntentLifecycleIntegrationTest {
    private val tenantId = UUID.randomUUID()
    private val subscriptionId = UUID.randomUUID()
    private val siteId = UUID.randomUUID()
    private val profileId = UUID.randomUUID()

    @Test
    fun `network simulator bng radius and hotspot boundaries preserve one lifecycle`() {
        val simulator = DeterministicNetworkSimulator(SimulatorProfiles.simulator)
        val intent = fixedIntent()
        val segments = Segments(ServiceSegmentState.PENDING)
        val access = Access()
        val activation = ServiceIntentLifecycleCoordinator(Intents(intent), segments, access, SubscriptionStatus("ACTIVE"))

        assertThat(simulator.create(320)).isEqualTo(SimulatorTerminalState.SUCCEEDED)
        segments.state = ServiceSegmentState.APPLIED
        activation.reconcile()
        val sessions = SimulatorBngAdapter(clock = { Instant.parse("2026-09-03T12:00:00Z") }).pollSessions(
            NasTarget("nas-1", "BRAS simulator", "SIMULATOR", "127.0.0.1", "SIMULATOR", listOf("qa-user")),
        )
        val hotspotIntent = ServiceIntent.createHotspot(tenantId, siteId, profileId)

        assertThat(access.activations).containsExactly(subscriptionId)
        assertThat(sessions).hasSize(1)
        assertThat(hotspotIntent.subjectKind).isEqualTo(ServiceIntentSubjectKind.HOTSPOT_SITE)
        assertThat(simulator.delete(320)).isEqualTo(SimulatorTerminalState.SUCCEEDED)
        assertThat(simulator.state().isEmpty()).isTrue()
    }

    @Test
    fun `fixed activation waits for applied segment and reconciliation activates once`() {
        val intent = fixedIntent()
        val segments = Segments(ServiceSegmentState.PENDING)
        val access = Access()
        val coordinator = ServiceIntentLifecycleCoordinator(Intents(intent), segments, access, SubscriptionStatus("ACTIVE"))

        coordinator.onActivated(subscriptionId)
        segments.state = ServiceSegmentState.APPLIED
        coordinator.reconcile()
        coordinator.onActivated(subscriptionId)

        assertThat(access.activations).containsExactly(subscriptionId)
    }

    @Test
    fun `fixed and voucher associations are exclusive`() {
        val voucher = ServiceIntent.createHotspot(tenantId, siteId, profileId)

        assertThat(voucher.subjectKind).isEqualTo(ServiceIntentSubjectKind.HOTSPOT_SITE)
        assertThat(voucher.hotspotSiteId).isEqualTo(siteId)
        assertThat(voucher.subscriptionId).isNull()
        assertThatThrownBy {
            ServiceIntent.rehydrate(
                UUID.randomUUID(), tenantId, subscriptionId, siteId, profileId,
                VlanEncapsulation.SINGLE_TAG, null, IntentStatus.DRAFT,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `isolation retains segment and delegates once to access owner`() {
        val intent = fixedIntent()
        val segments = Segments(ServiceSegmentState.APPLIED)
        val access = Access()
        val coordinator = ServiceIntentLifecycleCoordinator(Intents(intent), segments, access, SubscriptionStatus("ISOLATED"))

        coordinator.onIsolated(subscriptionId)
        coordinator.onIsolated(subscriptionId)

        assertThat(access.isolations).containsExactly(subscriptionId)
        assertThat(segments.state).isEqualTo(ServiceSegmentState.APPLIED)
    }

    @Test
    fun `operator suspend and restore update authoritative intent and access owner`() {
        val intent = fixedIntent()
        val access = Access()
        val service = ProvisioningIntentLifecycleService(Intents(intent), access)

        assertThat(service.suspend(intent.id).status).isEqualTo(IntentStatus.SUSPENDED)
        assertThat(service.restore(intent.id).status).isEqualTo(IntentStatus.ACTIVE)
        assertThat(access.isolations).containsExactly(subscriptionId)
        assertThat(access.activations).containsExactly(subscriptionId)
    }

    @Test
    fun `termination waits for removed segment and reconciliation terminates once`() {
        val intent = fixedIntent()
        val segments = Segments(ServiceSegmentState.APPLIED)
        val access = Access()
        val coordinator = ServiceIntentLifecycleCoordinator(Intents(intent), segments, access, SubscriptionStatus("TERMINATED"))

        coordinator.onTerminated(subscriptionId)
        segments.state = ServiceSegmentState.REMOVED
        coordinator.reconcile()
        coordinator.onTerminated(subscriptionId)

        assertThat(access.terminations).containsExactly(subscriptionId)
    }

    private fun fixedIntent() = ServiceIntent.rehydrate(
        UUID.randomUUID(), tenantId, subscriptionId, profileId,
        VlanEncapsulation.SINGLE_TAG, null, IntentStatus.ACTIVE,
    )

    private class Intents(private val intent: ServiceIntent) :
        com.duluin.ftth.provisioning.application.port.outbound.ServiceIntentRepository {
        override fun save(value: ServiceIntent) = value
        override fun findById(id: UUID) = intent.takeIf { it.id == id }
        override fun findAll() = listOf(intent)
        override fun findBySubscriptionId(subscriptionId: UUID) =
            intent.takeIf { it.subscriptionId == subscriptionId }
        override fun findByHotspotSiteId(siteId: UUID) = intent.takeIf { it.hotspotSiteId == siteId }
    }

    private class Segments(var state: ServiceSegmentState) : ServiceSegmentStatePort {
        override fun stateOf(intentId: UUID) = state
    }

    private class SubscriptionStatus(private val status: String) : SubscriptionLifecycleStatusPort {
        override fun statusOf(subscriptionId: UUID) = status
    }

    private class Access : SubscriberAccessLifecyclePort {
        val activations = mutableListOf<UUID>()
        val isolations = mutableListOf<UUID>()
        val terminations = mutableListOf<UUID>()
        private val states = mutableMapOf<UUID, String>()

        override fun status(subscriptionId: UUID) = states[subscriptionId]
        override fun activate(subscriptionId: UUID) {
            if (states[subscriptionId] != "ACTIVE") activations += subscriptionId
            states[subscriptionId] = "ACTIVE"
        }
        override fun isolate(subscriptionId: UUID) {
            if (states[subscriptionId] != "ISOLATED") isolations += subscriptionId
            states[subscriptionId] = "ISOLATED"
        }
        override fun terminate(subscriptionId: UUID) {
            if (states[subscriptionId] != "TERMINATED") terminations += subscriptionId
            states[subscriptionId] = "TERMINATED"
        }
    }
}
