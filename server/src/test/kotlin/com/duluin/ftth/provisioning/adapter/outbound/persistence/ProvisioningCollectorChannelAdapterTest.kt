package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.domain.model.AttemptStatus
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.ExecutionStepStatus
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class ProvisioningCollectorChannelAdapterTest {
    @Test
    fun `dispatch payload binds immutable management evidence source`() {
        val payload = provisioningPayload(
            mapOf(
                "intentId" to "intent-1",
                "vlanId" to "110",
                "safety.managementSourceId" to "0199386e-9718-7000-8000-000000000201",
                "safety.managementSourceType" to "TOPOLOGY_OBSERVATION",
                "safety.managementComplete" to "true",
                "safety.vendor" to "MIKROTIK",
                "preconditionHash" to "a".repeat(64),
            ),
        )

        assertThat(payload.values.managementSourceId).isEqualTo("0199386e-9718-7000-8000-000000000201")
        assertThat(payload.values.managementSourceType).isEqualTo("TOPOLOGY_OBSERVATION")
        assertThat(payload.values.intentId).isEqualTo("intent-1")
        assertThat(payload.values.vlanId).isEqualTo("110")
    }

    @Test
    fun `same collector stale read still requires guarded dispatched claim before emit`() {
        val tenantId = UUID.fromString("0199386e-9718-7000-8000-000000000101")
        val collectorId = UUID.fromString("0199386e-9718-7000-8000-000000000102")
        val deviceId = UUID.fromString("0199386e-9718-7000-8000-000000000103")
        val executionStepId = UUID.fromString("0199386e-9718-7000-8000-000000000104")
        val attempt = StepAttemptJpaEntity(
            id = UUID.fromString("0199386e-9718-7000-8000-000000000105"),
            executionStepId = executionStepId,
            phase = ExecutionPhase.APPLY,
            attemptNumber = 1,
            idempotencyKey = "same-owner-stale",
            fencingToken = 1,
            deadline = Instant.parse("2026-09-02T12:05:00Z"),
            status = AttemptStatus.DISPATCHED,
            errorCode = null,
            startedAt = Instant.parse("2026-09-02T12:00:00Z"),
            completedAt = null,
            collectorId = collectorId,
        )
        val executionStep = ExecutionStepJpaEntity(
            id = executionStepId,
            executionId = UUID.fromString("0199386e-9718-7000-8000-000000000106"),
            planStepId = UUID.fromString("0199386e-9718-7000-8000-000000000107"),
            stepOrder = 1,
            deviceKind = DeviceKind.BRAS,
            deviceId = deviceId,
            status = ExecutionStepStatus.PENDING,
            beforeHash = null,
            afterHash = null,
            lastError = null,
        )
        val attempts = mock(StepAttemptJpaRepository::class.java)
        val executionSteps = mock(ExecutionStepJpaRepository::class.java)
        `when`(attempts.findByStatusOrderByStartedAt(AttemptStatus.DISPATCHED)).thenReturn(listOf(attempt))
        `when`(executionSteps.findById(executionStepId)).thenReturn(Optional.of(executionStep))
        `when`(attempts.claimCollector(attempt.id, collectorId, deviceId)).thenReturn(0)
        val adapter = ProvisioningCollectorChannelAdapter(
            attempts,
            executionSteps,
            mock(ProvisionExecutionJpaRepository::class.java),
            mock(ProvisionPlanJpaRepository::class.java),
            mock(ProvisionStepJpaRepository::class.java),
            mock(ProvisionStepAttributeJpaRepository::class.java),
            mock(EntityManager::class.java),
            mock(CollectorCapabilityEvidenceWriter::class.java),
            Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC),
        )

        val pending = TenantContext.runAs(tenantId) {
            adapter.pendingFor(collectorId, tenantId, setOf(deviceId.toString()))
        }

        assertThat(pending).isEmpty()
        verify(attempts).claimCollector(attempt.id, collectorId, deviceId)
    }
}
