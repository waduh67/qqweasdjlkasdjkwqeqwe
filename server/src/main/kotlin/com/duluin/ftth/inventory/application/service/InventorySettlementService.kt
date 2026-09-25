package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialPlanningStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialSettlementStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialUsageStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialReworkStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventorySettlementService(private val plans: MaterialPlanningStore, private val usage: MaterialUsageStore,
    private val store: MaterialSettlementStore, private val reworks: MaterialReworkStore) : InventorySettlementApi {
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
        val planIds = reworks.planIds(plan.id)
        val deployments = store.deployments(context.workOrderId, planIds)
        if (deployments.any { it.actorId !in context.activeAssigneeIds })
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (snapshot.workOrderId != context.workOrderId || snapshot.customerId != context.customerId ||
            snapshot.planId != plan.id || snapshot.planRevision != plan.planRevision || snapshot.materialMode != plan.materialMode ||
            snapshot.actorId !in context.activeAssigneeIds || snapshot.workOrderRevision > context.workOrderRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        when (plan.materialMode) {
            MaterialMode.NONE -> if (plan.reason.isNullOrBlank() || snapshot.reason.isNullOrBlank() || snapshot.lines.isNotEmpty() || deployments.isNotEmpty())
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            MaterialMode.MATERIAL_REQUIRED -> {
                val rework = reworks.get(plan.id)
                val expected = (plan.lines + rework?.inheritedLines.orEmpty()).map { it.id }.toSet()
                val consumed = if (rework == null) snapshot.lines.map { it.planLineId }.toSet()
                    else reworks.usageIds(context.workOrderId, planIds).flatMap { usage.get(it).lines }.map { it.planLineId }.toSet()
                val reported = consumed + deployments.map { it.planLineId }
                if (reported.isEmpty() || reported != expected) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MaterialSettlementSource(plan.id, plan.planRevision, plan.materialMode, plan.reason, id, snapshot.useRevision, hash, body, documents, deployments)
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
