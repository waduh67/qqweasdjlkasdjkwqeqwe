package com.duluin.ftth.workorder

import com.duluin.ftth.InMemoryObjectStorage
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.DeleteGuard
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.common.storage.StoredObjectMetadata
import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignatureJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignatureJpaEntity
import com.duluin.ftth.workorder.application.service.EvidenceRetentionAuditJpaEntity
import com.duluin.ftth.workorder.application.service.EvidenceRetentionAuditJpaRepository
import com.duluin.ftth.workorder.application.service.EvidenceRetentionWorker
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.time.Instant
import java.util.UUID

class EvidenceRetentionWorkerTest {
    @Test
    fun `old eligible evidence is deleted tombstoned and audited`() = runCase(EvidenceRevisionState.COMMITTED, present = true) { entry, audits, storage ->
        assertThat(entry.state).isEqualTo(EvidenceRevisionState.TOMBSTONED)
        assertThat(audits.single().outcome).isEqualTo("EVIDENCE_DELETED")
        assertThat(runCatching { storage.get(entry.objectKey) }.isFailure).isTrue()
    }

    @Test
    fun `legal hold and missing object are audited without deletion`() = runCase(EvidenceRevisionState.LEGAL_HOLD, present = true) { entry, audits, storage ->
        assertThat(entry.state).isEqualTo(EvidenceRevisionState.LEGAL_HOLD)
        assertThat(audits.single().outcome).isEqualTo("EVIDENCE_LEGAL_HOLD_SKIP")
        assertThat(storage.get(entry.objectKey).size).isEqualTo(1)
    }

    @Test
    fun `missing eligible object finalizes hidden metadata safely`() = runCase(EvidenceRevisionState.SUPERSEDED, present = false) { entry, audits, _ ->
        assertThat(entry.state).isEqualTo(EvidenceRevisionState.TOMBSTONED)
        assertThat(audits.single().outcome).isEqualTo("EVIDENCE_DELETED")
    }

    @Test
    fun `conditional delete conflict preserves concurrently replaced bytes`() = runCase(EvidenceRevisionState.COMMITTED, present = true, storageFactory = { base, entry ->
        object : ObjectStorage by base {
            override fun deleteIfMatch(tenantId: String, key: String, guard: DeleteGuard): Boolean {
                base.put(key, "image/png", byteArrayOf(2))
                return base.deleteIfMatch(tenantId, key, guard)
            }
        }
    }) { entry, audits, storage ->
        assertThat(entry.state).isEqualTo(EvidenceRevisionState.COMMITTED)
        assertThat(audits.single().outcome).isEqualTo("EVIDENCE_CONDITIONAL_DELETE_CONFLICT")
        assertThat(storage.get(entry.objectKey).bytes).containsExactly(2)
    }

    @Test
    fun `metadata changed before delete preserves bytes and records outcome`() {
        val tenant = UUID.randomUUID(); val workOrderId = UUID.randomUUID(); val revisionId = UUID.randomUUID()
        val entry = EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, "$tenant/wo/$workOrderId/evidence/$revisionId", "hash", 1, "image/png", UUID.randomUUID(), "RAW_EVIDENCE_24M", EvidenceRevisionState.COMMITTED, null)
        val changed = WorkOrderEvidenceJpaEntity(revisionId, workOrderId, com.duluin.ftth.workorder.domain.model.EvidenceKind.OTHER, "caption", "$tenant/wo/$workOrderId/evidence/replaced", "image/png", 1, null, null, null, UUID.randomUUID(), Instant.now(), "hash", "image/png", 1, EvidenceRevisionState.SUPERSEDED, null)
        val registry = mock(EvidenceObjectRegistryJpaRepository::class.java); val evidence = mock(WorkOrderEvidenceJpaRepository::class.java); val signatures = mock(WorkOrderSignatureJpaRepository::class.java); val audit = mock(EvidenceRetentionAuditJpaRepository::class.java); val storage = InMemoryObjectStorage()
        storage.put(entry.objectKey, "image/png", byteArrayOf(1))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.LEGAL_HOLD)).thenReturn(emptyList()); `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.COMMITTED)).thenReturn(listOf(entry)); `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.SUPERSEDED)).thenReturn(emptyList()); `when`(registry.findByRevisionId(entry.revisionId)).thenReturn(entry); `when`(evidence.findByRevisionId(entry.revisionId)).thenReturn(changed); `when`(signatures.findByRevisionId(entry.revisionId)).thenReturn(null); `when`(audit.save(any(EvidenceRetentionAuditJpaEntity::class.java))).thenAnswer { it.arguments[0] }
        TenantContext.runAs(tenant) { EvidenceRetentionWorker(registry, evidence, signatures, storage, audit).purge(Instant.now().plusSeconds(1)) }
        val captor = ArgumentCaptor.forClass(EvidenceRetentionAuditJpaEntity::class.java); verify(audit).save(captor.capture())
        assertThat(captor.value.outcome).isEqualTo("EVIDENCE_METADATA_CHANGED"); assertThat(storage.get(entry.objectKey).size).isEqualTo(1); assertThat(entry.state).isEqualTo(EvidenceRevisionState.COMMITTED)
    }

    @Test
    fun `recent evidence is retained`() = runCase(EvidenceRevisionState.COMMITTED, present = true, cutoff = Instant.now().minusSeconds(1), expectAudit = false) { entry, audits, storage ->
        assertThat(audits).isEmpty(); assertThat(entry.state).isEqualTo(EvidenceRevisionState.COMMITTED); assertThat(storage.get(entry.objectKey).size).isEqualTo(1)
    }

    @Test
    fun `old signature is tombstoned and audited with signature outcome`() {
        val tenant = UUID.randomUUID(); val workOrderId = UUID.randomUUID(); val revisionId = UUID.randomUUID()
        val entry = EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, "$tenant/wo/$workOrderId/signature/$revisionId", null, 1, "image/png", UUID.randomUUID(), "RAW_EVIDENCE_24M", EvidenceRevisionState.COMMITTED, null)
        val source = WorkOrderSignatureJpaEntity(revisionId, workOrderId, "Customer", entry.objectKey, "image/png", 1, UUID.randomUUID(), Instant.now(), Instant.now(), null, "image/png", 1, EvidenceRevisionState.COMMITTED, null)
        val registry = mock(EvidenceObjectRegistryJpaRepository::class.java)
        val evidence = mock(WorkOrderEvidenceJpaRepository::class.java)
        val signatures = mock(WorkOrderSignatureJpaRepository::class.java)
        val audit = mock(EvidenceRetentionAuditJpaRepository::class.java)
        val storage = InMemoryObjectStorage()
        storage.put(entry.objectKey, "image/png", byteArrayOf(1))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.LEGAL_HOLD)).thenReturn(emptyList())
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.COMMITTED)).thenReturn(listOf(entry))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.SUPERSEDED)).thenReturn(emptyList())
        `when`(registry.findByRevisionId(revisionId)).thenReturn(entry)
        `when`(evidence.findByRevisionId(revisionId)).thenReturn(null)
        `when`(signatures.findByRevisionId(revisionId)).thenReturn(source)
        `when`(audit.save(any(EvidenceRetentionAuditJpaEntity::class.java))).thenAnswer { it.arguments[0] }

        TenantContext.runAs(tenant) { EvidenceRetentionWorker(registry, evidence, signatures, storage, audit).purge(Instant.now().plusSeconds(1)) }

        assertThat(source.revisionState).isEqualTo(EvidenceRevisionState.TOMBSTONED)
        val captor = ArgumentCaptor.forClass(EvidenceRetentionAuditJpaEntity::class.java)
        verify(audit).save(captor.capture())
        assertThat(captor.value.outcome).isEqualTo("SIGNATURE_DELETED")
    }

    @Test
    fun `restart recovers a claim left after bytes were deleted`() {
        val tenant = UUID.randomUUID(); val workOrderId = UUID.randomUUID(); val revisionId = UUID.randomUUID()
        val entry = EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, "$tenant/wo/$workOrderId/evidence/$revisionId", null, 1, "image/png", UUID.randomUUID(), "RAW_EVIDENCE_24M", EvidenceRevisionState.COMMITTED, null)
        val source = WorkOrderEvidenceJpaEntity(revisionId, workOrderId, com.duluin.ftth.workorder.domain.model.EvidenceKind.OTHER, "caption", entry.objectKey, "image/png", 1, null, null, null, UUID.randomUUID(), Instant.now(), null, "image/png", 1, EvidenceRevisionState.COMMITTED, null)
        val registry = mock(EvidenceObjectRegistryJpaRepository::class.java)
        val evidence = mock(WorkOrderEvidenceJpaRepository::class.java)
        val signatures = mock(WorkOrderSignatureJpaRepository::class.java)
        val audit = mock(EvidenceRetentionAuditJpaRepository::class.java)
        val storage = InMemoryObjectStorage()
        storage.put(entry.objectKey, "image/png", byteArrayOf(1))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.LEGAL_HOLD)).thenReturn(emptyList())
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.COMMITTED)).thenReturn(listOf(entry))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.SUPERSEDED)).thenReturn(emptyList())
        `when`(registry.findByTenantIdAndPurgeState(tenant, "CLAIMED")).thenReturn(listOf(entry))
        `when`(registry.findByRevisionId(revisionId)).thenReturn(entry)
        `when`(evidence.findByRevisionId(revisionId)).thenReturn(source)
        `when`(signatures.findByRevisionId(revisionId)).thenReturn(null)
        `when`(audit.save(any(EvidenceRetentionAuditJpaEntity::class.java))).thenAnswer { it.arguments[0] }

        assertThatThrownBy {
            TenantContext.runAs(tenant) {
                EvidenceRetentionWorker(registry, evidence, signatures, storage, audit, afterDelete = { error("simulated crash") })
                    .purge(Instant.now().plusSeconds(1))
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(entry.purgeState).isEqualTo("CLAIMED")
        assertThat(source.purgeState).isEqualTo("CLAIMED")

        TenantContext.runAs(tenant) {
            EvidenceRetentionWorker(registry, evidence, signatures, storage, audit).purge(Instant.now().plusSeconds(1))
        }

        assertThat(entry.state).isEqualTo(EvidenceRevisionState.TOMBSTONED)
        assertThat(source.revisionState).isEqualTo(EvidenceRevisionState.TOMBSTONED)
        assertThat(entry.purgeState).isEqualTo("DELETED")
    }

    @Test
    fun `metadata mutation after claim leaves both rows hidden for reconciliation`() {
        val tenant = UUID.randomUUID(); val workOrderId = UUID.randomUUID(); val revisionId = UUID.randomUUID()
        val entry = EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, "$tenant/wo/$workOrderId/evidence/$revisionId", null, 1, "image/png", UUID.randomUUID(), "RAW_EVIDENCE_24M", EvidenceRevisionState.COMMITTED, null)
        val source = WorkOrderEvidenceJpaEntity(revisionId, workOrderId, com.duluin.ftth.workorder.domain.model.EvidenceKind.OTHER, "caption", entry.objectKey, "image/png", 1, null, null, null, UUID.randomUUID(), Instant.now(), null, "image/png", 1, EvidenceRevisionState.COMMITTED, null)
        val registry = mock(EvidenceObjectRegistryJpaRepository::class.java); val evidence = mock(WorkOrderEvidenceJpaRepository::class.java); val signatures = mock(WorkOrderSignatureJpaRepository::class.java); val audit = mock(EvidenceRetentionAuditJpaRepository::class.java); val storage = InMemoryObjectStorage()
        storage.put(entry.objectKey, "image/png", byteArrayOf(1))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.LEGAL_HOLD)).thenReturn(emptyList()); `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.COMMITTED)).thenReturn(listOf(entry)); `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.SUPERSEDED)).thenReturn(emptyList()); `when`(registry.findByTenantIdAndPurgeState(tenant, "CLAIMED")).thenReturn(emptyList()); `when`(registry.findByRevisionId(revisionId)).thenReturn(entry); `when`(evidence.findByRevisionId(revisionId)).thenReturn(source); `when`(signatures.findByRevisionId(revisionId)).thenReturn(null); `when`(audit.save(any(EvidenceRetentionAuditJpaEntity::class.java))).thenAnswer { it.arguments[0] }

        TenantContext.runAs(tenant) {
            EvidenceRetentionWorker(registry, evidence, signatures, storage, audit, afterDelete = {
                source.storageKey = "$tenant/wo/$workOrderId/evidence/replaced"
            }).purge(Instant.now().plusSeconds(1))
        }

        assertThat(entry.purgeState).isEqualTo("RECONCILE")
        assertThat(source.purgeState).isEqualTo("RECONCILE")
        assertThat(runCatching { storage.get(entry.objectKey) }.isFailure).isTrue()
    }

    @Test
    fun `legal hold mutation before authorization prevents byte deletion`() {
        val tenant = UUID.randomUUID(); val workOrderId = UUID.randomUUID(); val revisionId = UUID.randomUUID()
        val entry = EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, "$tenant/wo/$workOrderId/evidence/$revisionId", null, 1, "image/png", UUID.randomUUID(), "RAW_EVIDENCE_24M", EvidenceRevisionState.COMMITTED, null)
        val source = WorkOrderEvidenceJpaEntity(revisionId, workOrderId, com.duluin.ftth.workorder.domain.model.EvidenceKind.OTHER, "caption", entry.objectKey, "image/png", 1, null, null, null, UUID.randomUUID(), Instant.now(), null, "image/png", 1, EvidenceRevisionState.COMMITTED, null)
        val registry = mock(EvidenceObjectRegistryJpaRepository::class.java); val evidence = mock(WorkOrderEvidenceJpaRepository::class.java); val signatures = mock(WorkOrderSignatureJpaRepository::class.java); val audit = mock(EvidenceRetentionAuditJpaRepository::class.java); val storage = InMemoryObjectStorage()
        storage.put(entry.objectKey, "image/png", byteArrayOf(1))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.LEGAL_HOLD)).thenReturn(emptyList()); `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.COMMITTED)).thenReturn(listOf(entry)); `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.SUPERSEDED)).thenReturn(emptyList()); `when`(registry.findByTenantIdAndPurgeState(tenant, "CLAIMED")).thenReturn(emptyList()); `when`(registry.findByRevisionId(revisionId)).thenReturn(entry); `when`(evidence.findByRevisionId(revisionId)).thenReturn(source); `when`(signatures.findByRevisionId(revisionId)).thenReturn(null); `when`(audit.save(any(EvidenceRetentionAuditJpaEntity::class.java))).thenAnswer { it.arguments[0] }

        TenantContext.runAs(tenant) {
            EvidenceRetentionWorker(registry, evidence, signatures, storage, audit, beforeDelete = {
                source.revisionState = EvidenceRevisionState.LEGAL_HOLD
            }).purge(Instant.now().plusSeconds(1))
        }

        assertThat(runCatching { storage.get(entry.objectKey) }.isSuccess).isTrue()
        assertThat(entry.purgeState).isEqualTo("RECONCILE")
        assertThat(source.purgeState).isEqualTo("RECONCILE")
    }

    private fun runCase(state: EvidenceRevisionState, present: Boolean, cutoff: Instant = Instant.now().plusSeconds(1), expectAudit: Boolean = true, storageFactory: (InMemoryObjectStorage, EvidenceObjectRegistryJpaEntity) -> ObjectStorage = { storage, _ -> storage }, assertion: (EvidenceObjectRegistryJpaEntity, List<EvidenceRetentionAuditJpaEntity>, InMemoryObjectStorage) -> Unit) {
        val tenant = UUID.randomUUID()
        val workOrderId = UUID.randomUUID()
        val revisionId = UUID.randomUUID()
        val entry = EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, "$tenant/wo/$workOrderId/evidence/$revisionId", "hash", 1, "image/png", UUID.randomUUID(), "RAW_EVIDENCE_24M", state, null)
        val registry = mock(EvidenceObjectRegistryJpaRepository::class.java)
        val evidence = mock(WorkOrderEvidenceJpaRepository::class.java)
        val signatures = mock(WorkOrderSignatureJpaRepository::class.java)
        val audit = mock(EvidenceRetentionAuditJpaRepository::class.java)
        val storage = InMemoryObjectStorage()
        if (present) storage.put(entry.objectKey, "image/png", byteArrayOf(1))
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.LEGAL_HOLD)).thenReturn(if (state == EvidenceRevisionState.LEGAL_HOLD) listOf(entry) else emptyList())
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.COMMITTED)).thenReturn(if (state == EvidenceRevisionState.COMMITTED) listOf(entry) else emptyList())
        `when`(registry.findByTenantIdAndState(tenant, EvidenceRevisionState.SUPERSEDED)).thenReturn(if (state == EvidenceRevisionState.SUPERSEDED) listOf(entry) else emptyList())
        `when`(registry.findByRevisionId(entry.revisionId)).thenReturn(entry)
        val source = WorkOrderEvidenceJpaEntity(entry.revisionId, workOrderId, com.duluin.ftth.workorder.domain.model.EvidenceKind.OTHER, "caption", entry.objectKey, "image/png", 1, null, null, null, UUID.randomUUID(), Instant.now(), "hash", "image/png", 1, state, null)
        `when`(evidence.findByRevisionId(entry.revisionId)).thenReturn(source)
        `when`(signatures.findByRevisionId(entry.revisionId)).thenReturn(null)
        `when`(audit.save(any(EvidenceRetentionAuditJpaEntity::class.java))).thenAnswer { it.arguments[0] }
        TenantContext.runAs(tenant) { EvidenceRetentionWorker(registry, evidence, signatures, storageFactory(storage, entry), audit).purge(cutoff) }
        val captor = ArgumentCaptor.forClass(EvidenceRetentionAuditJpaEntity::class.java)
        val audits = if (!expectAudit) {
            verifyNoInteractions(audit)
            emptyList()
        } else {
            verify(audit, atLeastOnce()).save(captor.capture())
            captor.allValues
        }
        assertion(entry, audits, storage)
        assertThat(source.revisionState).isEqualTo(
            if (audits.any { it.outcome == "EVIDENCE_DELETED" }) EvidenceRevisionState.TOMBSTONED else state,
        )
    }
}
