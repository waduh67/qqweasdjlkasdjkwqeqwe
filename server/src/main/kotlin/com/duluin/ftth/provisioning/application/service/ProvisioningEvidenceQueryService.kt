package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.provisioning.adapter.outbound.persistence.NormalizedStateJsonCodec
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class CapabilityEvidenceView(
    val id: UUID,
    val deviceKind: String,
    val deviceId: UUID,
    val vendor: String,
    val model: String,
    val firmware: String,
    val transport: String,
    val operationClass: String,
    val supported: Boolean,
    val observedAt: Instant,
    val expiresAt: Instant,
)

data class ManagementProtectionView(
    val id: UUID,
    val deviceKind: String,
    val deviceId: UUID,
    val revision: Int,
    val complete: Boolean,
    val sourceType: String?,
    val sourceEvidenceId: UUID?,
    val validUntil: Instant,
)

data class DriftView(
    val id: UUID,
    val deviceKind: String,
    val deviceId: UUID,
    val revision: Int,
    val status: String,
    val recordedAt: Instant,
)

data class ObservationView(val id: UUID, val deviceKind: String, val deviceId: UUID, val observedAt: Instant)

data class ExecutionTimelineEntry(
    val stepOrder: Int,
    val attemptNumber: Int,
    val phase: String,
    val status: String,
    val errorCode: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
)

@Service
@Transactional(readOnly = true)
class ProvisioningEvidenceQueryService(
    private val entityManager: EntityManager,
    private val codec: NormalizedStateJsonCodec,
    private val classifier: ProvisioningDriftClassifier,
    private val revisions: ProvisioningResourceRevisionStore,
    private val currentUser: CurrentUserProvider,
    private val audit: ProvisioningAuditPublisher,
) {
    fun capabilities(): List<CapabilityEvidenceView> = entityManager.createNativeQuery(
        """SELECT id, device_kind, device_id, vendor, model, firmware, transport, operation_class,
                  supported, observed_at, expires_at
           FROM provisioning_capability_evidence ORDER BY observed_at DESC""",
    ).resultList.map { raw ->
        val row = raw as Array<*>
        CapabilityEvidenceView(
            row[0] as UUID, row[1] as String, row[2] as UUID, row[3] as String, row[4] as String,
            row[5] as String, row[6] as String, row[7] as String, row[8] as Boolean,
            row[9] as Instant, row[10] as Instant,
        )
    }

    fun protections(): List<ManagementProtectionView> = entityManager.createNativeQuery(
        """SELECT id, device_kind, device_id, complete, source_type,
                  COALESCE(topology_source_id, device_observation_source_id), valid_until
           FROM provisioning_management_safety_evidence ORDER BY updated_at DESC""",
    ).resultList.map { raw ->
        val row = raw as Array<*>
        ManagementProtectionView(
            row[0] as UUID, row[1] as String, row[2] as UUID,
            revisions.current(MANAGEMENT_PROTECTION, row[2] as UUID), row[3] as Boolean,
            row[4] as String?, row[5] as UUID?, row[6] as Instant,
        )
    }

    fun drift(): List<DriftView> = entityManager.createNativeQuery(
        "SELECT id, device_kind, device_id, status, recorded_at FROM provisioning_drift_record ORDER BY recorded_at DESC",
    ).resultList.map { raw ->
        val row = raw as Array<*>
        val id = row[0] as UUID
        DriftView(id, row[1] as String, row[2] as UUID, revisions.current(DRIFT, id), row[3] as String, row[4] as Instant)
    }

    fun observations(): List<ObservationView> = entityManager.createNativeQuery(
        "SELECT id, device_kind, device_id, observed_at FROM provisioning_device_observation ORDER BY observed_at DESC",
    ).resultList.map { raw ->
        val row = raw as Array<*>
        ObservationView(row[0] as UUID, row[1] as String, row[2] as UUID, row[3] as Instant)
    }

    fun timeline(executionId: UUID): List<ExecutionTimelineEntry> {
        val exists = entityManager.createNativeQuery("SELECT count(*) FROM provisioning_execution WHERE id = :id")
            .setParameter("id", executionId).singleResult as Number
        if (exists.toLong() == 0L) throw NotFoundException("EXECUTION_NOT_FOUND")
        return entityManager.createNativeQuery(
            """SELECT step.step_order, attempt.attempt_number, attempt.phase, attempt.status,
                      attempt.error_code, attempt.started_at, attempt.completed_at
               FROM provisioning_execution_step step
               JOIN provisioning_step_attempt attempt
                 ON attempt.execution_step_id = step.id AND attempt.tenant_id = step.tenant_id
               WHERE step.execution_id = :id
               ORDER BY step.step_order, attempt.started_at, attempt.attempt_number""",
        ).setParameter("id", executionId).resultList.map { raw ->
            val row = raw as Array<*>
            ExecutionTimelineEntry(
                (row[0] as Number).toInt(), (row[1] as Number).toInt(), row[2] as String, row[3] as String,
                row[4] as String?, row[5] as Instant, row[6] as Instant?,
            )
        }
    }

    @Transactional
    fun adoptDrift(id: UUID, revision: Int): DriftView {
        val actor = currentUser.currentOrNull()?.takeIf { it.hasPermission("provisioning.drift.adopt") }
            ?: throw AccessDeniedException("DRIFT_ADOPTION_PERMISSION_REQUIRED")
        val rows = entityManager.createNativeQuery(
            """SELECT drift.device_kind, drift.device_id, drift.snapshot_id, drift.observation_id,
                      snapshot.plan_id, snapshot.normalized_state::text, observation.normalized_state::text, drift.status
               FROM provisioning_drift_record drift
               JOIN provisioning_device_snapshot snapshot
                 ON snapshot.id = drift.snapshot_id AND snapshot.tenant_id = drift.tenant_id
               JOIN provisioning_device_observation observation
                 ON observation.id = drift.observation_id AND observation.tenant_id = drift.tenant_id
               WHERE drift.id = :id FOR UPDATE OF drift""",
        ).setParameter("id", id).resultList
        val row = (rows.singleOrNull() as? Array<*>) ?: throw NotFoundException("DRIFT_NOT_FOUND")
        if (row[7] as String != "BENIGN" || !classifier.semanticallyEquivalent(
                codec.decode(row[5] as String), codec.decode(row[6] as String),
            )) throw ConflictException("DRIFT_NOT_SEMANTICALLY_EQUIVALENT")
        requireAdoptionSafety(row[0] as String, row[1] as UUID)
        revisions.advance(DRIFT, id, revision)
        val baselineSnapshotId = UuidV7.generate()
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_device_snapshot
               (id, tenant_id, device_kind, device_id, plan_id, normalized_state, captured_at)
               VALUES (:id, current_setting('app.tenant_id')::uuid, :kind, :device, :plan, CAST(:state AS jsonb), now())""",
        ).setParameter("id", baselineSnapshotId).setParameter("kind", row[0]).setParameter("device", row[1])
            .setParameter("plan", row[4]).setParameter("state", row[6]).executeUpdate()
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_adoption_baseline
               (id, tenant_id, device_kind, device_id, drift_id, snapshot_id, observation_id, adopted_by, adopted_at)
               VALUES (:baselineId, current_setting('app.tenant_id')::uuid, :kind, :device, :drift, :snapshot,
                       :observation, :actor, now())""",
        ).setParameter("baselineId", UuidV7.generate()).setParameter("kind", row[0]).setParameter("device", row[1])
            .setParameter("drift", id).setParameter("snapshot", baselineSnapshotId).setParameter("observation", row[3])
            .setParameter("actor", actor.userId).executeUpdate()
        entityManager.createNativeQuery("UPDATE provisioning_drift_record SET status = 'NONE', updated_at = now() WHERE id = :id")
            .setParameter("id", id).executeUpdate()
        audit.publish(ProvisioningAuditRecord(
            actor.tenantId, "provisioning.drift.adopted", "DriftRecord", id,
            mapOf("snapshotId" to baselineSnapshotId.toString(), "observationId" to row[3].toString()),
        ))
        return drift().single { it.id == id }
    }

    private fun requireAdoptionSafety(deviceKind: String, deviceId: UUID) {
        val allowed = entityManager.createNativeQuery(
            """SELECT
                 EXISTS (SELECT 1 FROM provisioning_management_safety_evidence
                         WHERE device_kind = :kind AND device_id = :device AND complete = true AND valid_until > now())
                 AND EXISTS (SELECT 1 FROM provisioning_adapter_certification
                             WHERE device_kind = :kind AND device_id = :device AND status = 'CERTIFIED'
                               AND revoked_at IS NULL AND valid_until > now())""",
        ).setParameter("kind", deviceKind).setParameter("device", deviceId).singleResult as Boolean
        if (!allowed) throw ConflictException("DRIFT_ADOPTION_SAFETY_BLOCKED")
    }

    private companion object {
        const val DRIFT = "DRIFT"
        const val MANAGEMENT_PROTECTION = "MANAGEMENT_PROTECTION"
    }
}
