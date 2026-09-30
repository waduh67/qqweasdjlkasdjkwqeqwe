package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationEvidenceStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.StoredMigrationEvidence
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class MigrationEvidenceReconciler(transactionManager: PlatformTransactionManager, private val store: MigrationEvidenceStore,
    private val storage: ObjectStorage) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        timeout = 5
    }

    fun reconcile(tenant: UUID, candidate: StoredMigrationEvidence): ReceiptEvidenceReconciliation {
        val expectedKey = "$tenant/warehouse/migrations/${candidate.view.batchId}/${candidate.view.caseId}/${candidate.view.id}"
        if (candidate.objectKey != expectedKey) return ReceiptEvidenceReconciliation.UNRESOLVED
        val committed = try {
            TenantContext.runAs(tenant) { transaction.execute { store.settledObjectKey(candidate.view.batchId, candidate.view.id) } }
        } catch (_: Exception) {
            log.warn("Migration evidence reconciliation required evidenceId={} reason=METADATA_UNAVAILABLE", candidate.view.id)
            return ReceiptEvidenceReconciliation.UNRESOLVED
        }
        if (committed != null) return ReceiptEvidenceReconciliation.RETAINED
        return try {
            storage.delete(candidate.objectKey)
            ReceiptEvidenceReconciliation.DELETED
        } catch (_: Exception) {
            log.warn("Migration evidence reconciliation required evidenceId={} reason=STORAGE_DELETE_FAILED", candidate.view.id)
            ReceiptEvidenceReconciliation.UNRESOLVED
        }
    }
}
