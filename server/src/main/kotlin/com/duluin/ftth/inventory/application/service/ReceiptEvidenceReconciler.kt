package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReceiptEvidencePersistence
import com.duluin.ftth.inventory.adapter.outbound.persistence.StoredReceiptEvidence
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

enum class ReceiptEvidenceReconciliation { RETAINED, DELETED, UNRESOLVED }

@Service
class ReceiptEvidenceReconciler(transactionManager: PlatformTransactionManager, private val evidence: ReceiptEvidencePersistence,
    private val storage: ObjectStorage) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        timeout = 5
    }

    fun reconcile(tenant: UUID, candidate: StoredReceiptEvidence): ReceiptEvidenceReconciliation {
        val committedKey = try {
            TenantContext.runAs(tenant) { transaction.execute { evidence.settledObjectKey(candidate.view.documentId, candidate.view.id) } }
        } catch (_: Exception) {
            log.warn("Receipt evidence reconciliation required evidenceId={} reason=METADATA_UNAVAILABLE", candidate.view.id)
            return ReceiptEvidenceReconciliation.UNRESOLVED
        }
        if (committedKey != null) return ReceiptEvidenceReconciliation.RETAINED
        return try {
            storage.delete(candidate.objectKey)
            ReceiptEvidenceReconciliation.DELETED
        } catch (_: Exception) {
            log.warn("Receipt evidence reconciliation required evidenceId={} reason=STORAGE_DELETE_FAILED", candidate.view.id)
            ReceiptEvidenceReconciliation.UNRESOLVED
        }
    }
}
