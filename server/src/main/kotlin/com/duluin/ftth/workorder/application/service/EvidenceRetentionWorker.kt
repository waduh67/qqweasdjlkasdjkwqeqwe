package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.common.storage.DeleteGuard
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignatureJpaRepository
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Entity
@Table(name = "evidence_retention_audit")
class EvidenceRetentionAuditJpaEntity(
    id: UUID = UUID.randomUUID(),
    tenantId: UUID,
    @Column(name = "revision_id", nullable = false, updatable = false) val revisionId: UUID,
    @Column(name = "object_key", nullable = false, updatable = false) val objectKey: String,
    @Column(name = "retention_class", nullable = false, updatable = false) val retentionClass: String,
    @Column(nullable = false, updatable = false) val outcome: String,
    @Column(nullable = false, updatable = false) val worker: String = "evidence-retention",
    @Column(name = "occurred_at", nullable = false, updatable = false) val occurredAt: Instant = Instant.now(),
) : TenantAwareJpaEntity(id) {
    init { this.tenantId = tenantId }
}

interface EvidenceRetentionAuditJpaRepository : org.springframework.data.jpa.repository.JpaRepository<EvidenceRetentionAuditJpaEntity, UUID>

@Component
class EvidenceRetentionScheduler(
    private val tenants: TenantApi,
    private val worker: EvidenceRetentionWorker,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneOffset.UTC,
) {
    @Scheduled(cron = "\${ftth.workorder.evidence-retention-cron:0 15 3 * * *}")
    fun purge() {
        val cutoff = Instant.now(clock).atZone(zone).minusMonths(24).toInstant()
        tenants.findActiveTenantIds().forEach { tenantId -> TenantContext.runAs(tenantId) { worker.purge(cutoff) } }
    }
}

@Component
class EvidenceRetentionWorker(
    private val registry: EvidenceObjectRegistryJpaRepository,
    private val evidence: WorkOrderEvidenceJpaRepository,
    private val signatures: WorkOrderSignatureJpaRepository,
    private val storage: ObjectStorage,
    private val audit: EvidenceRetentionAuditJpaRepository,
    private val txManager: PlatformTransactionManager? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val afterRegistryClaim: () -> Unit = {},
    private val beforeDelete: () -> Unit = {},
    private val afterDelete: () -> Unit = {},
) {
    fun purge(cutoff: Instant) {
        val tenantId = TenantContext.tenantId()
        registry.findByTenantIdAndState(tenantId, EvidenceRevisionState.LEGAL_HOLD)
            .filter { it.purgeState == "ACTIVE" && it.createdAt < cutoff }
            .forEach { record(it, "${kindFor(it)}_LEGAL_HOLD_SKIP") }
        registry.findByTenantIdAndPurgeState(tenantId, "CLAIMED").forEach { recover(it, tenantId) }
        val candidates = registry.findByTenantIdAndState(tenantId, EvidenceRevisionState.COMMITTED) +
            registry.findByTenantIdAndState(tenantId, EvidenceRevisionState.SUPERSEDED)
        candidates.filter { it.purgeState == "ACTIVE" && it.createdAt < cutoff }.forEach { process(it, tenantId) }
    }

    private fun process(candidate: EvidenceObjectRegistryJpaEntity, tenantId: UUID) {
        val claim = transaction { claim(candidate.revisionId, tenantId) } ?: return
        beforeDelete()
        execute(claim, tenantId)
    }

    private fun recover(candidate: EvidenceObjectRegistryJpaEntity, tenantId: UUID) {
        val claim = transaction { claim(candidate.revisionId, tenantId, recoverClaim = true) } ?: return
        beforeDelete()
        execute(claim, tenantId)
    }

    private fun execute(claim: PurgeClaim, tenantId: UUID) {
        val metadata = try {
            storage.head(tenantId.toString(), claim.objectKey)
        } catch (_: NotFoundException) {
            finalize(claim, tenantId)
            return
        }
        val authorized = authorize(claim, tenantId) ?: return
        if (!storage.deleteIfMatch(tenantId.toString(), authorized.objectKey, DeleteGuard(metadata.etag, metadata.version))) {
            unclaim(claim, tenantId, "${claim.kind}_CONDITIONAL_DELETE_CONFLICT")
            return
        }
        afterDelete()
        finalize(claim, tenantId)
    }

    private fun authorize(claim: PurgeClaim, tenantId: UUID): PurgeClaim? = transaction {
        val current = registry.findByRevisionId(claim.revisionId) ?: return@transaction null
        val source = EvidenceRetentionSource.resolve(claim.revisionId, evidence, signatures)
        if (source == null || current.purgeState != "CLAIMED" || current.purgeClaimId != claim.claimId ||
            !source.matches(current, tenantId, "CLAIMED") || source.claimId != claim.claimId
        ) {
            if (source != null) markReconcile(current, source, "${claim.kind}_RECONCILE_BEFORE_DELETE")
            return@transaction null
        }
        claim.copy(registryVersion = current.rowVersion, sourceVersion = source.rowVersion)
    }

    private fun claim(revisionId: UUID, tenantId: UUID, recoverClaim: Boolean = false): PurgeClaim? {
        val current = registry.findByRevisionId(revisionId) ?: return null
        val source = EvidenceRetentionSource.resolve(revisionId, evidence, signatures)
        val claimId = current.purgeClaimId
        if (recoverClaim && (current.purgeState != "CLAIMED" || claimId == null)) return null
        if (!recoverClaim && (current.purgeState != "ACTIVE" || current.state !in ELIGIBLE)) return null
        if (source == null) {
            record(current, "SOURCE_METADATA_CHANGED")
            return null
        }
        if (source.state == EvidenceRevisionState.LEGAL_HOLD) {
            if (!recoverClaim) record(current, "${source.kind}_LEGAL_HOLD_SKIP")
            return null
        }
        val token = claimId ?: UUID.randomUUID()
        if (!source.matches(current, tenantId, if (recoverClaim) "CLAIMED" else "ACTIVE")) {
            if (recoverClaim) markReconcile(current, source, "${source.kind}_RECONCILE_AFTER_DELETE")
            else record(current, "${source.kind}_METADATA_CHANGED")
            return null
        }
        if (!recoverClaim) {
            current.purgeState = "CLAIMED"
            current.purgeClaimId = token
            current.purgeClaimedAt = Instant.now(clock)
            registry.save(current)
            afterRegistryClaim()
            // CLAIMED registry state is only an in-transaction precondition; source must still be ACTIVE before its claim.
            val lockedSource = EvidenceRetentionSource.resolve(revisionId, evidence, signatures)
                ?: error("retention source disappeared during claim")
            if (!lockedSource.matches(current, tenantId, "ACTIVE")) {
                error("retention source changed during claim")
            }
            setClaimed(revisionId, token)
        }
        return PurgeClaim(current.revisionId, current.objectKey, source.kind, token)
    }

    private fun setClaimed(revisionId: UUID, token: UUID) {
        val photo = evidence.findByRevisionId(revisionId)
        val signature = signatures.findByRevisionId(revisionId)
        photo?.let { it.purgeState = "CLAIMED"; it.purgeClaimId = token; it.purgeClaimedAt = Instant.now(clock); evidence.save(it) }
        signature?.let { it.purgeState = "CLAIMED"; it.purgeClaimId = token; it.purgeClaimedAt = Instant.now(clock); signatures.save(it) }
    }

    private fun finalize(claim: PurgeClaim, tenantId: UUID) = transaction {
        val current = registry.findByRevisionId(claim.revisionId) ?: return@transaction
        val source = EvidenceRetentionSource.resolve(claim.revisionId, evidence, signatures)
        if (source == null || current.purgeState != "CLAIMED" || current.purgeClaimId != claim.claimId ||
            !source.matches(current, tenantId, "CLAIMED") || source.claimId != claim.claimId
        ) {
            if (source != null) markReconcile(current, source, "${claim.kind}_RECONCILE_AFTER_DELETE")
            return@transaction
        }
        source.tombstone()
        current.state = EvidenceRevisionState.TOMBSTONED
        current.purgeState = "DELETED"
        current.purgeClaimId = null
        current.purgeClaimedAt = null
        registry.save(current)
        record(current, "${claim.kind}_DELETED")
    }

    private fun unclaim(claim: PurgeClaim, tenantId: UUID, outcome: String) = transaction {
        val current = registry.findByRevisionId(claim.revisionId) ?: return@transaction
        val source = EvidenceRetentionSource.resolve(claim.revisionId, evidence, signatures)
        if (source != null && current.purgeClaimId == claim.claimId) {
            current.purgeState = "ACTIVE"; current.purgeClaimId = null; current.purgeClaimedAt = null; registry.save(current)
            clearClaim(claim.revisionId)
            record(current, outcome)
        }
    }

    private fun clearClaim(revisionId: UUID) {
        evidence.findByRevisionId(revisionId)?.let { it.purgeState = "ACTIVE"; it.purgeClaimId = null; it.purgeClaimedAt = null; evidence.save(it) }
        signatures.findByRevisionId(revisionId)?.let { it.purgeState = "ACTIVE"; it.purgeClaimId = null; it.purgeClaimedAt = null; signatures.save(it) }
    }

    private fun markReconcile(registryRow: EvidenceObjectRegistryJpaEntity, source: EvidenceRetentionSource, outcome: String) {
        source.reconcile()
        registryRow.purgeState = "RECONCILE"; registry.save(registryRow)
        record(registryRow, outcome)
    }

    private fun record(entry: EvidenceObjectRegistryJpaEntity, outcome: String) = audit.save(
        EvidenceRetentionAuditJpaEntity(tenantId = TenantContext.tenantId(), revisionId = entry.revisionId, objectKey = entry.objectKey, retentionClass = entry.retentionClass, outcome = outcome),
    )

    private fun kindFor(entry: EvidenceObjectRegistryJpaEntity): String = when {
        "/signature/" in entry.objectKey -> "SIGNATURE"
        "/evidence/" in entry.objectKey -> "EVIDENCE"
        else -> "SOURCE"
    }

    private fun <T> transaction(block: () -> T): T = txManager?.let { TransactionTemplate(it).execute { block() }!! } ?: block()

    private data class PurgeClaim(
        val revisionId: UUID,
        val objectKey: String,
        val kind: String,
        val claimId: UUID,
        val registryVersion: Long = -1,
        val sourceVersion: Long = -1,
    )

    private companion object {
        val ELIGIBLE = setOf(EvidenceRevisionState.COMMITTED, EvidenceRevisionState.SUPERSEDED)
    }
}
