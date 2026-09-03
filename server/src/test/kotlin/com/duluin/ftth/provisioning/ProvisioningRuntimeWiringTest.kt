package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.adapter.outbound.persistence.CollectorBackedProvisioningDeviceGateway
import com.duluin.ftth.provisioning.adapter.outbound.persistence.CollectorResultReceipt
import com.duluin.ftth.provisioning.adapter.outbound.persistence.CollectorResultReceiptReader
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionRunner
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.service.DispatchableProvisioningWork
import com.duluin.ftth.provisioning.application.service.EngineProvisioningExecutionRunner
import com.duluin.ftth.provisioning.application.service.ProvisioningExecutionEngine
import com.duluin.ftth.provisioning.application.service.ProvisioningExecutionQueueWorker
import com.duluin.ftth.provisioning.application.service.ProvisioningWorkflowService
import com.duluin.ftth.provisioning.application.service.AuthoritativePlanCompilationService
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.collector.adapter.SimulatorBngAdapter
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.simulator.network.DeterministicNetworkSimulator
import com.duluin.ftth.simulator.network.SimulatorProfiles
import com.duluin.ftth.simulator.network.SimulatorTerminalState
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ProvisioningRuntimeWiringTest {
    private val now = Instant.parse("2026-09-03T12:00:00Z")

    @Test
    fun `production runtime classes are registered as Spring components`() {
        assertThat(ProvisioningExecutionEngine::class.java.getAnnotation(Component::class.java)).isNotNull
        assertThat(EngineProvisioningExecutionRunner::class.java.getAnnotation(Component::class.java)).isNotNull
        assertThat(ProvisioningWorkflowService::class.java.getAnnotation(Service::class.java)).isNotNull
        assertThat(ProvisioningExecutionQueueWorker::class.java.getAnnotation(Component::class.java)).isNotNull
        assertThat(CollectorBackedProvisioningDeviceGateway::class.java.getAnnotation(Component::class.java)).isNotNull
    }

    @Test
    fun `queue worker drains runnable executions inside their tenant`() {
        val tenantId = UuidV7.generate()
        val execution = ProvisionExecution.queue(tenantId, UuidV7.generate(), UuidV7.generate(), "queue-key")
        val tenants = mock(TenantApi::class.java)
        val executions = mock(ProvisionExecutionRepository::class.java)
        val runner = mock(ProvisioningExecutionRunner::class.java)
        val compilation = mock(AuthoritativePlanCompilationService::class.java)
        `when`(tenants.findActiveTenantIds()).thenReturn(listOf(tenantId))
        `when`(executions.findRunnable(10)).thenReturn(listOf(execution))
        `when`(runner.run(execution.id, "provisioning-queue")).thenReturn(execution)

        ProvisioningExecutionQueueWorker(
            tenants, executions, runner, ProvisioningRolloutProperties(autoApplyEnabled = true), compilation, 10,
        ).drain()

        verify(runner).run(execution.id, "provisioning-queue")
        verify(compilation).completeDeprovisionIfNeeded(execution)
    }

    @Test
    fun `queue worker remains inert while production auto apply is disabled`() {
        val tenants = mock(TenantApi::class.java)
        val executions = mock(ProvisionExecutionRepository::class.java)
        val runner = mock(ProvisioningExecutionRunner::class.java)
        val compilation = mock(AuthoritativePlanCompilationService::class.java)

        ProvisioningExecutionQueueWorker(tenants, executions, runner, ProvisioningRolloutProperties(), compilation, 10).drain()

        verify(tenants, org.mockito.Mockito.never()).findActiveTenantIds()
    }

    @Test
    fun `collector gateway converts accepted receipt into normalized device state`() {
        val reader = mock(CollectorResultReceiptReader::class.java)
        val work = DispatchableProvisioningWork(
            UuidV7.generate(), UuidV7.generate(), 1, UuidV7.generate(),
            DeviceReference(DeviceKind.OLT, UuidV7.generate()), ProvisionOperation.ENSURE_ACCESS_PORT,
            ExecutionPhase.VERIFY, "receipt-key", 1, "a".repeat(64), now.plusSeconds(30), mapOf("vlanId" to "320"),
        )
        `when`(reader.find("receipt-key", ExecutionPhase.VERIFY, work.fencingToken)).thenReturn(
            receipt(work, true, listOf(320)),
        )

        val observed = CollectorBackedProvisioningDeviceGateway(reader, Clock.fixed(now, ZoneOffset.UTC)).observe(work)

        assertThat(observed.matchesDesired).isTrue()
        assertThat(observed.state.values[NormalizedField.VLANS]).isEqualTo(
            NormalizedValue.sequence(NormalizedValue.number(320)),
        )
    }

    @Test
    fun `collector receipt simulator bng radius and hotspot boundaries converge`() {
        val simulator = DeterministicNetworkSimulator(SimulatorProfiles.simulator)
        assertThat(simulator.create(320)).isEqualTo(SimulatorTerminalState.SUCCEEDED)
        val work = DispatchableProvisioningWork(
            UuidV7.generate(), UuidV7.generate(), 1, UuidV7.generate(),
            DeviceReference(DeviceKind.OLT, UuidV7.generate()), ProvisionOperation.ENSURE_ACCESS_PORT,
            ExecutionPhase.VERIFY, "integrated-receipt", 1, "a".repeat(64), now.plusSeconds(30),
            mapOf("vlanId" to "320"),
        )
        val reader = mock(CollectorResultReceiptReader::class.java)
        `when`(reader.find("integrated-receipt", ExecutionPhase.VERIFY, work.fencingToken)).thenReturn(
            receipt(work, true, simulator.state().olt.vlans.toList()),
        )
        val observed = CollectorBackedProvisioningDeviceGateway(reader, Clock.fixed(now, ZoneOffset.UTC)).observe(work)
        val sessions = SimulatorBngAdapter(clock = { now }).pollSessions(
            NasTarget("nas-1", "BRAS simulator", "SIMULATOR", "127.0.0.1", "SIMULATOR", listOf("voucher-qa")),
        )
        val hotspotIntent = ServiceIntent.createHotspot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        assertThat(observed.matchesDesired).isTrue()
        assertThat(observed.state.values[NormalizedField.VLANS]).isEqualTo(
            NormalizedValue.sequence(NormalizedValue.number(320)),
        )
        assertThat(sessions).hasSize(1)
        assertThat(hotspotIntent.hotspotSiteId).isNotNull()
        assertThat(simulator.delete(320)).isEqualTo(SimulatorTerminalState.SUCCEEDED)
    }

    private fun gatewayWork(phase: ExecutionPhase, key: String) = DispatchableProvisioningWork(
        UuidV7.generate(), UuidV7.generate(), 1, UuidV7.generate(),
        DeviceReference(DeviceKind.OLT, UuidV7.generate()), ProvisionOperation.ENSURE_ACCESS_PORT,
        phase, key, 1, "a".repeat(64), now.plusSeconds(30), mapOf("vlanId" to "320"),
    )

    private fun receipt(
        work: DispatchableProvisioningWork,
        verificationMatches: Boolean?,
        vlanIds: List<Int>?,
    ) = CollectorResultReceipt(
        work.idempotencyKey,
        when (work.phase) {
            ExecutionPhase.PREFLIGHT, ExecutionPhase.ROLLBACK_CHECK -> "PREFLIGHT"
            ExecutionPhase.APPLY -> "APPLY"
            ExecutionPhase.VERIFY, ExecutionPhase.ROLLBACK_VERIFY -> "VERIFY"
            ExecutionPhase.COMPENSATE -> "ROLLBACK"
        },
        work.fencingToken,
        true,
        null,
        verificationMatches,
        vlanIds,
        vlanIds?.size,
        vlanIds?.let { ids ->
            val canonical = "managedResourceCount=${ids.size};vlanIds=${ids.distinct().sorted().joinToString(",")}"
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        },
    )
}
