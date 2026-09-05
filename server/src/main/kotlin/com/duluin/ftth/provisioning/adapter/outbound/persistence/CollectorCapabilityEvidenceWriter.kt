package com.duluin.ftth.provisioning.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.contract.acknowledgementKey
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class OwnedCapabilityReport(
    val collectorId: UUID,
    val tenantId: UUID,
    val target: ProvisioningTarget,
    val report: DeviceCapabilityReport,
)

@Component
class CollectorCapabilityEvidenceWriter(
    private val entityManager: EntityManager,
    private val clock: Clock,
    @Value("\${ftth.provisioning.capability-max-age:PT5M}")
    private val capabilityMaxAge: Duration,
) {
    fun persist(owned: OwnedCapabilityReport): String? {
        val now = clock.instant()
        val expiresAt = owned.report.reportedAt.plus(capabilityMaxAge)
        if (owned.report.reportedAt.isAfter(now) || !expiresAt.isAfter(now)) return null
        if (owned.report.operationClasses.any { it !in SUPPORTED_OPERATION_CLASSES }) return null
        if (listOf(
                owned.report.fingerprint.vendor,
                owned.report.fingerprint.model,
                owned.report.fingerprint.firmware,
                owned.report.fingerprint.transport,
            ).any { !NORMALIZED_FINGERPRINT.matches(it) }
        ) return null
        val deviceId = runCatching { UUID.fromString(owned.target.deviceId) }.getOrNull() ?: return null
        val write = CapabilityEvidenceWrite(owned, deviceId, expiresAt, owned.report.acknowledgementKey())
        val inserted = insertReport(write)
        val reportId = findReportId(write)
        if (inserted) {
            owned.report.operationClasses.forEach { operationClass ->
                insertOperationEvidence(write, reportId, operationClass)
            }
        }
        return write.key
    }

    private fun insertReport(write: CapabilityEvidenceWrite): Boolean {
        val owned = write.owned
        val report = owned.report
        val inserted = entityManager.createNativeQuery(
            """INSERT INTO provisioning_collector_device_report
               (id, tenant_id, collector_id, report_key, target_id, vendor, model, firmware, transport,
                capabilities, operation_classes, reported_at, expires_at)
               VALUES (:id, :tenant, :collector, :key, :target, :vendor, :model, :firmware, :transport,
                :capabilities, :operations, :reportedAt, :expiresAt)
               ON CONFLICT (tenant_id, collector_id, report_key) DO NOTHING""",
        ).setParameter("id", UuidV7.generate()).setParameter("tenant", owned.tenantId)
            .setParameter("collector", owned.collectorId).setParameter("key", write.key)
            .setParameter("target", report.targetId).setParameter("vendor", report.fingerprint.vendor)
            .setParameter("model", report.fingerprint.model).setParameter("firmware", report.fingerprint.firmware)
            .setParameter("transport", report.fingerprint.transport)
            .setParameter("capabilities", report.capabilities.sorted().joinToString("\n"))
            .setParameter("operations", report.operationClasses.sorted().joinToString("\n"))
            .setParameter("reportedAt", report.reportedAt).setParameter("expiresAt", write.expiresAt).executeUpdate()
        return inserted == 1
    }

    private fun findReportId(write: CapabilityEvidenceWrite): UUID =
        entityManager.createNativeQuery(
            """SELECT id FROM provisioning_collector_device_report
               WHERE tenant_id = :tenant AND collector_id = :collector AND report_key = :key""",
        ).setParameter("tenant", write.owned.tenantId).setParameter("collector", write.owned.collectorId)
            .setParameter("key", write.key)
            .singleResult as UUID

    private fun insertOperationEvidence(
        write: CapabilityEvidenceWrite,
        reportId: UUID,
        operationClass: String,
    ) {
        val owned = write.owned
        val report = owned.report
        entityManager.createNativeQuery(
            """INSERT INTO provisioning_capability_evidence
               (id, tenant_id, collector_id, report_id, device_kind, device_id, vendor, model, firmware,
                transport, operation_class, supported, observed_at, expires_at)
               VALUES (:id, :tenant, :collector, :report, :kind, :device, :vendor, :model, :firmware,
                :transport, :operation, true, :observedAt, :expiresAt)
               ON CONFLICT (tenant_id, report_id, operation_class) DO NOTHING""",
        ).setParameter("id", UuidV7.generate()).setParameter("tenant", owned.tenantId)
            .setParameter("collector", owned.collectorId).setParameter("report", reportId)
            .setParameter("kind", owned.target.deviceKind).setParameter("device", write.deviceId)
            .setParameter("vendor", report.fingerprint.vendor).setParameter("model", report.fingerprint.model)
            .setParameter("firmware", report.fingerprint.firmware).setParameter("transport", report.fingerprint.transport)
            .setParameter("operation", operationClass).setParameter("observedAt", report.reportedAt)
            .setParameter("expiresAt", write.expiresAt).executeUpdate()
    }

    private data class CapabilityEvidenceWrite(
        val owned: OwnedCapabilityReport,
        val deviceId: UUID,
        val expiresAt: java.time.Instant,
        val key: String,
    )

    private companion object {
        val SUPPORTED_OPERATION_CLASSES = ProvisionOperation.entries.mapTo(hashSetOf(), ProvisionOperation::name)
        val NORMALIZED_FINGERPRINT = Regex("^[A-Za-z0-9._:/-]{1,120}$")
    }
}
