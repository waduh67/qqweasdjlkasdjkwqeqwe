package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationFailure
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationOutcome
import com.duluin.ftth.provisioning.application.port.outbound.ProvisioningObservationPort
import com.duluin.ftth.provisioning.domain.model.DeviceSnapshot
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class ProvisioningObservationPersistenceAdapter(
    private val entityManager: EntityManager,
    private val codec: NormalizedStateJsonCodec,
    private val clock: Clock,
) : ProvisioningObservationPort {
    /** Creates or consumes an asynchronous collector readback request; it never reuses the baseline as an observation. */
    @Transactional
    override fun observe(baseline: DeviceSnapshot) = when (val request = latestRequest(baseline.id)) {
        null -> requestObservation(baseline)
        else -> consumeOrRetry(baseline, request)
    }

    private fun consumeOrRetry(baseline: DeviceSnapshot, request: ObservationRequestRow) = when (request.status) {
        "SUCCEEDED" -> {
            val state = codec.decode(requireNotNull(request.normalizedState))
            if (request.stateHash != NormalizedStateHash.sha256(state)) {
                markConsumed(request.id)
                ProvisioningObservationOutcome.Unavailable(ProvisioningObservationFailure.READBACK_HASH_MISMATCH)
            } else {
                markConsumed(request.id)
                ProvisioningObservationOutcome.Available(state, requireNotNull(request.observedAt))
            }
        }
        "PENDING" -> if (request.deadline.isAfter(clock.instant())) ProvisioningObservationOutcome.Pending else expire(request.id)
        "FAILED" -> {
            markConsumed(request.id)
            ProvisioningObservationOutcome.Unavailable(ProvisioningObservationFailure.READBACK_UNAVAILABLE)
        }
        "CONSUMED" -> requestObservation(baseline)
        else -> ProvisioningObservationOutcome.Unavailable(ProvisioningObservationFailure.READBACK_UNAVAILABLE)
    }

    private fun expire(requestId: UUID): ProvisioningObservationOutcome {
        entityManager.createNativeQuery(
            "UPDATE provisioning_observation_request SET status = 'FAILED', error_code = 'TIMEOUT', updated_at = now() WHERE id = :id",
        ).setParameter("id", requestId).executeUpdate()
        return ProvisioningObservationOutcome.Unavailable(ProvisioningObservationFailure.READBACK_UNAVAILABLE)
    }

    private fun requestObservation(baseline: DeviceSnapshot): ProvisioningObservationOutcome {
        val step = entityManager.createNativeQuery(
            """SELECT step.id, plan.revision, step.operation
               FROM provisioning_step step
               JOIN provisioning_plan plan ON plan.id = step.plan_id AND plan.tenant_id = step.tenant_id
               WHERE step.plan_id = :plan AND step.device_kind = :kind AND step.device_id = :device
               ORDER BY step.step_order LIMIT 1""",
        ).setParameter("plan", baseline.planId).setParameter("kind", baseline.device.kind.name)
            .setParameter("device", baseline.device.id).resultList.singleOrNull() as? Array<*>
            ?: return ProvisioningObservationOutcome.Unavailable(ProvisioningObservationFailure.READBACK_UNAVAILABLE)
        val requestId = UuidV7.generate()
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_observation_request
               (id, tenant_id, baseline_snapshot_id, plan_id, plan_revision, step_id, device_kind, device_id,
                operation_class, baseline_hash, deadline, status)
               VALUES (:id, current_setting('app.tenant_id')::uuid, :baseline, :plan, :revision, :step,
                       :kind, :device, :operation, :hash, :deadline, 'PENDING')""",
        ).setParameter("id", requestId).setParameter("baseline", baseline.id).setParameter("plan", baseline.planId)
            .setParameter("revision", (step[1] as Number).toInt()).setParameter("step", step[0])
            .setParameter("kind", baseline.device.kind.name).setParameter("device", baseline.device.id)
            .setParameter("operation", step[2]).setParameter("hash", NormalizedStateHash.sha256(baseline.state))
            .setParameter("deadline", clock.instant().plus(REQUEST_TIMEOUT)).executeUpdate()
        return ProvisioningObservationOutcome.Pending
    }

    private fun latestRequest(baselineId: UUID): ObservationRequestRow? = entityManager.createNativeQuery(
        """SELECT id, status, deadline, state_hash, normalized_state::text, observed_at
           FROM provisioning_observation_request WHERE baseline_snapshot_id = :baseline
           ORDER BY created_at DESC LIMIT 1 FOR UPDATE""",
    ).setParameter("baseline", baselineId).resultList.singleOrNull()?.let { raw ->
        val row = raw as Array<*>
        ObservationRequestRow(
            row[0] as UUID, row[1] as String, row[2] as Instant, row[3] as String?, row[4] as String?, row[5] as Instant?,
        )
    }

    private fun markConsumed(requestId: UUID) {
        entityManager.createNativeQuery(
            "UPDATE provisioning_observation_request SET status = 'CONSUMED', updated_at = now() WHERE id = :id",
        ).setParameter("id", requestId).executeUpdate()
    }

    private data class ObservationRequestRow(
        val id: UUID,
        val status: String,
        val deadline: Instant,
        val stateHash: String?,
        val normalizedState: String?,
        val observedAt: Instant?,
    )

    private companion object { val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(5) }
}
