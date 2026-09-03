package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.integration.CollectorProvisioningChannel
import com.duluin.ftth.common.integration.ProvisioningDispatch
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.ProvisioningAcknowledgement
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
class ProvisioningObservationChannelAdapter(
    private val entityManager: EntityManager,
    private val attributes: ProvisionStepAttributeJpaRepository,
    private val codec: NormalizedStateJsonCodec,
    private val clock: Clock,
) : CollectorProvisioningChannel {
    @Transactional
    override fun pendingFor(collectorId: UUID, tenantId: UUID, availableTargetIds: Set<String>): List<ProvisioningDispatch> {
        require(TenantContext.tenantId() == tenantId) { "TENANT_OWNERSHIP_MISMATCH" }
        return entityManager.createNativeQuery(
            """SELECT id, plan_id, plan_revision, step_id, device_kind, device_id,
                      operation_class, baseline_hash, deadline
               FROM provisioning_observation_request
               WHERE status = 'PENDING' AND deadline > :now
               ORDER BY created_at""",
        ).setParameter("now", clock.instant()).resultList.mapNotNull { raw -> dispatch(raw as Array<*>, collectorId, availableTargetIds) }
    }

    @Transactional
    override fun accept(
        collectorId: UUID,
        tenantId: UUID,
        availableTargets: Map<String, ProvisioningTarget>,
        results: List<ProvisioningStepResult>,
        reports: List<DeviceCapabilityReport>,
    ): ProvisioningAcknowledgement {
        require(TenantContext.tenantId() == tenantId) { "TENANT_OWNERSHIP_MISMATCH" }
        val accepted = results.mapNotNull { acceptResult(collectorId, availableTargets, it) }
            .toSet()
        return ProvisioningAcknowledgement(resultAttemptIds = accepted)
    }

    private fun dispatch(row: Array<*>, collectorId: UUID, availableTargetIds: Set<String>): ProvisioningDispatch? {
        val requestId = row[0] as UUID
        val deviceId = row[5] as UUID
        if (deviceId.toString() !in availableTargetIds || !claim(requestId, collectorId)) return null
        val values = attributes.findByStepIdIn(listOf(row[3] as UUID)).associate { it.attributeKey to it.attributeValue }
        return ProvisioningDispatch(
            (row[1] as UUID).toString(),
            (row[2] as Number).toInt(),
            (row[3] as UUID).toString(),
            requestId.toString(),
            ProvisioningCommandPhase.PREFLIGHT,
            row[6] as String,
            "observation:$requestId",
            0,
            row[7] as String,
            row[8] as Instant,
            deviceId.toString(),
            row[4] as String,
            provisioningPayload(values),
            observationOnly = true,
        )
    }

    private fun claim(requestId: UUID, collectorId: UUID): Boolean = entityManager.createNativeQuery(
        """UPDATE provisioning_observation_request SET collector_id = :collector, updated_at = now()
           WHERE id = :id AND status = 'PENDING' AND (collector_id IS NULL OR collector_id = :collector)""",
    ).setParameter("collector", collectorId).setParameter("id", requestId).executeUpdate() == 1

    private fun acceptResult(
        collectorId: UUID,
        availableTargets: Map<String, ProvisioningTarget>,
        result: ProvisioningStepResult,
    ): String? {
        val requestId = result.attemptId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val context = requestContext(requestId) ?: return null
        if (!context.matches(collectorId, availableTargets, result)) return null
        if (clock.instant().isAfter(context.deadline)) return null
        if (context.status in setOf("SUCCEEDED", "FAILED")) return requestId.toString()
        if (context.status != "PENDING") return null
        val verification = result.verification
        if (!result.success || verification == null) {
            fail(requestId, result.errorCode?.name ?: "OBSERVATION_UNAVAILABLE")
            return requestId.toString()
        }
        if (verification.observedAt.isBefore(context.createdAt) || verification.observedAt.isAfter(context.deadline) ||
            verification.stateHash != verification.state.observationHash()
        ) return null
        val state = verification.state.toDomain()
        val updated = entityManager.createNativeQuery(
            """UPDATE provisioning_observation_request
               SET status = 'SUCCEEDED', state_hash = :hash, normalized_state = CAST(:state AS jsonb),
                   observed_at = :observedAt, updated_at = :acceptedAt
               WHERE id = :id AND status = 'PENDING'""",
        ).setParameter("hash", NormalizedStateHash.sha256(state)).setParameter("state", codec.encode(state))
            .setParameter("observedAt", verification.observedAt).setParameter("acceptedAt", clock.instant())
            .setParameter("id", requestId).executeUpdate()
        return requestId.toString().takeIf { updated == 1 }
    }

    private fun requestContext(requestId: UUID): ObservationRequestContext? = entityManager.createNativeQuery(
        """SELECT status, collector_id, plan_id, plan_revision, step_id, device_id, device_kind,
                  operation_class, created_at, deadline
           FROM provisioning_observation_request WHERE id = :id FOR UPDATE""",
    ).setParameter("id", requestId).resultList.singleOrNull()?.let { raw ->
        val row = raw as Array<*>
        ObservationRequestContext(
            row[0] as String, row[1] as UUID?, row[2] as UUID, (row[3] as Number).toInt(), row[4] as UUID,
            row[5] as UUID, row[6] as String, row[7] as String, row[8] as Instant, row[9] as Instant,
        )
    }

    private fun fail(requestId: UUID, errorCode: String) {
        entityManager.createNativeQuery(
            """UPDATE provisioning_observation_request SET status = 'FAILED', error_code = :error, updated_at = :now
               WHERE id = :id AND status = 'PENDING'""",
        ).setParameter("error", errorCode).setParameter("now", clock.instant()).setParameter("id", requestId).executeUpdate()
    }

    private fun ProvisioningResultState.toDomain(): NormalizedDeviceState = if (vlanIds.isEmpty()) {
        NormalizedDeviceState.empty()
    } else {
        NormalizedDeviceState.of(
            NormalizedField.VLANS to NormalizedValue.Sequence.of(vlanIds.map(NormalizedValue::number)),
        )
    }

    private data class ObservationRequestContext(
        val status: String,
        val collectorId: UUID?,
        val planId: UUID,
        val revision: Int,
        val stepId: UUID,
        val deviceId: UUID,
        val deviceKind: String,
        val operationClass: String,
        val createdAt: Instant,
        val deadline: Instant,
    ) {
        fun matches(
            expectedCollectorId: UUID,
            availableTargets: Map<String, ProvisioningTarget>,
            result: ProvisioningStepResult,
        ): Boolean = collectorId == expectedCollectorId && availableTargets[deviceId.toString()]?.deviceKind == deviceKind &&
            result.phase == ProvisioningCommandPhase.PREFLIGHT &&
            result.fencingEpoch == 0L && result.idempotencyKey == "observation:${result.attemptId}" &&
            result.planId == planId.toString() && result.revision == revision && result.stepId == stepId.toString() &&
            result.targetId == deviceId.toString() && result.operationClass == operationClass &&
            !result.completedAt.isBefore(createdAt) && !result.completedAt.isAfter(deadline)
    }
}
