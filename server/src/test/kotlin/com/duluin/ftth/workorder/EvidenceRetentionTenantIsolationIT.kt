package com.duluin.ftth.workorder

import com.duluin.ftth.InMemoryObjectStorage
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryPersistenceAdapter
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidencePersistenceAdapter
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignatureJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignatureJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignaturePersistenceAdapter
import com.duluin.ftth.workorder.application.service.EvidenceRetentionAuditJpaEntity
import com.duluin.ftth.workorder.application.service.EvidenceRetentionAuditJpaRepository
import com.duluin.ftth.workorder.application.service.EvidenceRetentionWorker
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class EvidenceRetentionTenantIsolationIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var registry: EvidenceObjectRegistryJpaRepository
    @Autowired private lateinit var registryTransitions: EvidenceObjectRegistryPersistenceAdapter
    @Autowired private lateinit var evidence: WorkOrderEvidenceJpaRepository
    @Autowired private lateinit var evidenceQuery: WorkOrderEvidencePersistenceAdapter
    @Autowired private lateinit var signatures: WorkOrderSignatureJpaRepository
    @Autowired private lateinit var signatureQuery: WorkOrderSignaturePersistenceAdapter
    @Autowired private lateinit var workOrders: WorkOrderRepository
    @Autowired private lateinit var audit: EvidenceRetentionAuditJpaRepository
    @Autowired private lateinit var worker: EvidenceRetentionWorker
    @Autowired private lateinit var storage: InMemoryObjectStorage

    @AfterEach
    fun clearTenantContext() = TenantContext.clear()

    @Test
    fun `retention reads and updates only active tenant rows under application role`() {
        val tenantA = tenant("retention-a")
        val tenantB = tenant("retention-b")
        val sourceA = source(tenantA, "a")
        val sourceB = source(tenantB, "b")
        seed(tenantA, sourceA, "SEED_A")
        seed(tenantB, sourceB, "SEED_B")

        asTenant(tenantA) {
            assertThat(registry.findAll()).extracting<UUID> { it.id }.containsExactly(sourceA.id)
            assertThat(registry.findById(sourceB.id)).isEmpty
            worker.purge(java.time.Instant.now().plusSeconds(1))
        }

        asTenant(tenantB) {
            assertThat(registry.findAll()).extracting<UUID> { it.id }.containsExactly(sourceB.id)
            assertThat(registry.findById(sourceA.id)).isEmpty
            assertThat(registry.findById(sourceB.id).orElseThrow().state)
                .isEqualTo(EvidenceRevisionState.COMMITTED)
            assertThat(audit.findAll()).extracting<String> { it.outcome }.containsExactly("SEED_B")
        }

        asTenant(tenantA) {
            assertThat(registry.findById(sourceA.id).orElseThrow().state)
                .isEqualTo(EvidenceRevisionState.TOMBSTONED)
            val workOrderId = sourceA.objectKey.split('/')[2].let(UUID::fromString)
            assertThat(evidenceQuery.listByWorkOrder(workOrderId)).isEmpty()
            assertThat(audit.findAll()).extracting<String> { it.outcome }
                .containsExactlyInAnyOrder("SEED_A", "EVIDENCE_DELETED")
        }
    }

    @Test
    fun `successful signature purge tombstones source and hides signature query`() {
        val tenantId = tenant("retention-signature")
        val workOrderId = asTenant(tenantId) {
            workOrders.save(
                WorkOrder.open(
                    tenantId, WorkOrderType.REPAIR, "Signature retention", null,
                    WorkOrderPriority.NORMAL, null, null, null, null, createdBy = UUID.randomUUID(),
                ),
            ).id
        }
        val revisionId = UUID.randomUUID()
        val key = "$tenantId/wo/$workOrderId/signature/$revisionId"
        val source = EvidenceObjectRegistryJpaEntity(
            id = UUID.randomUUID(), revisionId = revisionId, objectKey = key,
            expectedSha256 = null, expectedSizeBytes = 1, expectedContentType = "image/png",
            actorId = UUID.randomUUID(), retentionClass = "RAW_EVIDENCE_24M",
            state = EvidenceRevisionState.COMMITTED, etag = null,
        )
        asTenant(tenantId) {
            registry.save(source)
            signatures.save(
                WorkOrderSignatureJpaEntity(
                    id = revisionId, workOrderId = workOrderId, signerName = "Customer",
                    storageKey = key, contentType = "image/png", sizeBytes = 1,
                    signedBy = UUID.randomUUID(), signedAt = java.time.Instant.now(),
                    receiptAt = java.time.Instant.now(), sha256 = null,
                    expectedContentType = "image/png", expectedSizeBytes = 1,
                    revisionState = EvidenceRevisionState.COMMITTED, correctionReason = null,
                ),
            )
            storage.put(key, "image/png", byteArrayOf(1))
        }
        asTenant(tenantId) {
            worker.purge(java.time.Instant.now().plusSeconds(1))
        }

        asTenant(tenantId) {
            assertThat(audit.findAll()).extracting<String> { it.outcome }
                .containsExactly("SIGNATURE_DELETED")
            assertThat(signatures.findById(revisionId).orElseThrow().revisionState)
                .isEqualTo(EvidenceRevisionState.TOMBSTONED)
            assertThat(signatureQuery.findByWorkOrder(workOrderId)).isNull()
        }
    }

    @Test
    fun `registry adapter allows legal hold before claim and rejects it after claim`() {
        val tenantId = tenant("retention-transition")
        val source = source(tenantId, "transition")
        seed(tenantId, source, "SEED")

        asTenant(tenantId) {
            registryTransitions.transition(source.revisionId, EvidenceRevisionState.LEGAL_HOLD, "operator hold")
        }
        assertThat(asTenant(tenantId) { registry.findById(source.id).orElseThrow().state })
            .isEqualTo(EvidenceRevisionState.LEGAL_HOLD)

        asTenant(tenantId) {
            val claimed = registry.findById(source.id).orElseThrow()
            claimed.purgeState = "CLAIMED"
            claimed.purgeClaimId = UUID.randomUUID()
            registry.save(claimed)
        }
        val before = asTenant(tenantId) { registry.findById(source.id).orElseThrow() }

        assertThatThrownBy {
            asTenant(tenantId) {
                registryTransitions.transition(source.revisionId, EvidenceRevisionState.LEGAL_HOLD, "raced hold")
            }
        }.isInstanceOf(com.duluin.ftth.common.domain.error.ConflictException::class.java)

        val after = asTenant(tenantId) { registry.findById(source.id).orElseThrow() }
        assertThat(after.state).isEqualTo(before.state)
        assertThat(after.purgeState).isEqualTo("CLAIMED")
        assertThat(after.purgeClaimId).isEqualTo(before.purgeClaimId)
        assertThatThrownBy {
            asTenant(tenantId) { registryTransitions.markCommitted(source.revisionId, "late") }
        }.isInstanceOf(com.duluin.ftth.common.domain.error.ConflictException::class.java)
    }

    @Test
    fun `unset and unknown tenant contexts fail closed`() {
        val tenantId = tenant("retention-closed")
        val source = source(tenantId, "closed")
        seed(tenantId, source, "SEED")

        TenantContext.clear()
        assertThat(TransactionTemplate(txManager).execute { registry.findAll() }).isEmpty()
        assertThat(TransactionTemplate(txManager).execute { audit.findAll() }).isEmpty()

        val unknownTenant = UUID.randomUUID()
        asTenant(unknownTenant) {
            assertThat(registry.findAll()).isEmpty()
            assertThat(audit.findAll()).isEmpty()
        }
    }

    private fun seed(tenantId: UUID, source: EvidenceObjectRegistryJpaEntity, outcome: String) {
        asTenant(tenantId) {
            registry.save(source)
            val workOrderId = source.objectKey.split('/')[2].let(UUID::fromString)
            evidence.save(
                WorkOrderEvidenceJpaEntity(
                    id = source.revisionId,
                    workOrderId = workOrderId,
                    kind = com.duluin.ftth.workorder.domain.model.EvidenceKind.OTHER,
                    caption = "retention",
                    storageKey = source.objectKey,
                    contentType = source.expectedContentType,
                    sizeBytes = source.expectedSizeBytes,
                    latitude = null,
                    longitude = null,
                    capturedAt = null,
                    uploadedBy = UUID.randomUUID(),
                    receiptAt = java.time.Instant.now(),
                    sha256 = source.expectedSha256,
                    expectedContentType = source.expectedContentType,
                    expectedSizeBytes = source.expectedSizeBytes,
                    revisionState = source.state,
                    correctionReason = null,
                ),
            )
            audit.save(
                EvidenceRetentionAuditJpaEntity(
                    tenantId = tenantId,
                    revisionId = source.revisionId,
                    objectKey = source.objectKey,
                    retentionClass = source.retentionClass,
                    outcome = outcome,
                ),
            )
            storage.put(source.objectKey, source.expectedContentType, byteArrayOf(1))
        }
    }

    private fun source(tenantId: UUID, suffix: String): EvidenceObjectRegistryJpaEntity {
        val workOrderId = asTenant(tenantId) {
            workOrders.save(
                WorkOrder.open(
                    tenantId = tenantId,
                    type = WorkOrderType.REPAIR,
                    title = "Retention $suffix",
                    description = null,
                    priority = WorkOrderPriority.NORMAL,
                    customerId = null,
                    incidentId = null,
                    areaId = null,
                    scheduledAt = null,
                    createdBy = UUID.randomUUID(),
                ),
            ).id
        }
        val revisionId = UUID.randomUUID()
        return EvidenceObjectRegistryJpaEntity(
            id = UUID.randomUUID(),
            revisionId = revisionId,
            objectKey = "$tenantId/wo/$workOrderId/evidence/$revisionId",
            expectedSha256 = null,
            expectedSizeBytes = 1,
            expectedContentType = "image/png",
            actorId = UUID.randomUUID(),
            retentionClass = "RAW_EVIDENCE_24M",
            state = EvidenceRevisionState.COMMITTED,
            etag = null,
        )
    }

    private fun tenant(prefix: String): UUID = tenantApi.ensureTenant(
        "$prefix-${UUID.randomUUID().toString().take(8)}",
        prefix,
    ).id

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
