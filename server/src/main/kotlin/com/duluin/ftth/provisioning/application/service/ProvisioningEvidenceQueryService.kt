package com.duluin.ftth.provisioning.application.service

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
    val complete: Boolean,
    val sourceType: String?,
    val sourceEvidenceId: UUID?,
    val validUntil: Instant,
)

data class DriftView(val id: UUID, val deviceKind: String, val deviceId: UUID, val status: String, val recordedAt: Instant)

@Service
@Transactional(readOnly = true)
class ProvisioningEvidenceQueryService(private val entityManager: EntityManager) {
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
            row[0] as UUID, row[1] as String, row[2] as UUID, row[3] as Boolean,
            row[4] as String?, row[5] as UUID?, row[6] as Instant,
        )
    }

    fun drift(): List<DriftView> = entityManager.createNativeQuery(
        "SELECT id, device_kind, device_id, status, recorded_at FROM provisioning_drift_record ORDER BY recorded_at DESC",
    ).resultList.map { raw ->
        val row = raw as Array<*>
        DriftView(row[0] as UUID, row[1] as String, row[2] as UUID, row[3] as String, row[4] as Instant)
    }

    @Transactional
    fun adoptDrift(id: UUID): DriftView {
        val updated = entityManager.createNativeQuery(
            "UPDATE provisioning_drift_record SET status = 'NONE', updated_at = now() WHERE id = :id",
        ).setParameter("id", id).executeUpdate()
        if (updated != 1) throw com.duluin.ftth.common.domain.error.NotFoundException("DRIFT_NOT_FOUND")
        return drift().single { it.id == id }
    }
}
