package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialPlanningStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialSettlementStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialUsageStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventorySettlementService(private val plans: MaterialPlanningStore, private val usage: MaterialUsageStore,
    private val store: MaterialSettlementStore) : InventorySettlementApi {
    override fun freeze(context: MaterialPlanningContext): MaterialSettlementSource {
        context.cutover.assertHeld()
        context.authority.assertHeld()
        val history = plans.current(context.workOrderId) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val plan = history.plan
        if (history.state != "SUBMITTED" || plan.customerId != context.customerId || plan.workType != context.workType ||
            plan.action != context.action || plan.workOrderRevision > context.workOrderRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val documents = store.lockDocuments(context.workOrderId)
        val id = store.usageId(context.workOrderId)
        usage.lockSources(store.receipts(id))
        store.lockUsage(id)
        val snapshot = usage.get(id)
        val body = usage.body(id)
        if (snapshot.workOrderId != context.workOrderId || snapshot.customerId != context.customerId ||
            snapshot.planId != plan.id || snapshot.planRevision != plan.planRevision || snapshot.materialMode != plan.materialMode ||
            snapshot.actorId !in context.activeAssigneeIds || snapshot.workOrderRevision > context.workOrderRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        when (plan.materialMode) {
            MaterialMode.NONE -> if (plan.reason.isNullOrBlank() || snapshot.reason.isNullOrBlank() || snapshot.lines.isNotEmpty())
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            MaterialMode.MATERIAL_REQUIRED -> if (snapshot.lines.isEmpty() ||
                snapshot.lines.map { it.planLineId }.toSet() != plan.lines.map { it.id }.toSet())
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MaterialSettlementSource(plan.id, plan.planRevision, plan.materialMode, plan.reason, id, snapshot.useRevision, hash, body, documents)
    }

    override fun verify(context: MaterialPlanningContext, approval: MaterialSettlementApproval): MaterialVerificationReceipt {
        context.cutover.assertHeld()
        context.authority.assertHeld()
        store.receipt(approval.id)?.let { return it }
        if (freeze(context) != approval.source) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val receipt = MaterialVerificationReceipt(approval.id, approval.source.usageId, approval.source.useRevision,
            when (approval.source.materialMode) { MaterialMode.NONE -> "NO_MATERIAL"; MaterialMode.MATERIAL_REQUIRED -> "VERIFIED" })
        store.record(approval, receipt)
        return receipt
    }
}
