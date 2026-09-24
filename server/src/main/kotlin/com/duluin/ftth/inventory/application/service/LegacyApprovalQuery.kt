package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseApprovalStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class LegacyApprovalQuery(private val authority: CurrentAuthorityApi, private val approvals: DurableApprovalService,
    private val store: WarehouseApprovalStore, private val queries: InventoryApprovalQueryApi, transactions: PlatformTransactionManager) {
    private val transaction = TransactionTemplate(transactions)
    @Transactional
    fun get(id: UUID): Map<String, Any?> {
        val view = approvals.get(id)
        val current = authority.lockCurrent()
        val record = store.get(id)
        val evaluation = record.snapshot.evaluation
        val amount = if (current.platformAdmin || "inventory.cost.view" in current.permissions) {
            val numerator = evaluation.valueNumerator?.toBigInteger()
            if (evaluation.valueDenominator == "1" && numerator != null && numerator <= java.math.BigInteger.valueOf(Long.MAX_VALUE)) numerator.toLong() else null
        } else null
        return mapOf("approvalId" to id, "type" to if (evaluation.operation == PolicyOperation.RECEIPT) "RESTOCK" else evaluation.operation.name,
            "amount" to amount, "requesterId" to record.snapshot.requesterId, "custodianId" to null,
            "requestedAt" to record.requestedAt, "expiresAt" to record.expiresAt, "status" to view.status, "revision" to view.revision,
            "decisions" to store.decisions(id).map { mapOf("tier" to it.tier, "approverId" to it.actorId,
                "decision" to it.decision, "reason" to it.reason, "decidedAt" to it.decidedAt) })
    }
    fun pending(): List<Map<String, Any?>> {
        val candidates = queries.list(WarehouseApprovalFilter(size = 100, status = WarehouseApprovalStatus.PENDING)).items
        return candidates.mapNotNull { candidate ->
            try {
                if (!queries.details(candidate.approval.requestId).actions.canDecide) null
                else transaction.execute { get(candidate.approval.requestId) }?.takeIf { it["status"] == WarehouseApprovalStatus.PENDING }
            } catch (failure: WarehouseContractException) {
                if (failure.error.code in setOf(WarehouseErrorCode.FORBIDDEN, WarehouseErrorCode.NOT_FOUND)) null else throw failure
            }
        }
    }
}
