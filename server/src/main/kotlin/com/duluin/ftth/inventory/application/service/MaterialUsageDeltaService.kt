package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class MaterialUsageDeltaService(private val authority: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val usage: MaterialUsageStore, private val deltas: MaterialUsageDeltaStore, private val residuals: MaterialResidualStore,
    private val lifecycle: MaterialLifecycleStore, private val totals: MaterialPhysicalTotalsStore, private val operations: WarehouseOperationStore,
    private val posting: WarehousePosting, private val scopes: InventoryWarehouseScopeApi, private val locations: WarehouseReceiptService,
    private val plans: MaterialPlanningStore, private val reworks: MaterialReworkStore, private val receipts: MaterialReceiptStore) {
    private val mapper = jacksonObjectMapper()

    fun append(context: MaterialPlanningContext, input: MaterialUsageDeltaRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val cutover = cutovers.lockForCommand(context.cutover.snapshot.epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "workorder.order.field")
        receiptKey(metadata.idempotencyKey)
        if (current.fence.identity.userId !in context.activeAssigneeIds) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (!input.quantityBase.matches(Regex("[1-9][0-9]{0,18}")) || input.quantityBase.toLongOrNull() == null ||
            input.reason.isBlank() || input.reason.length > 1000 || input.evidenceReference.isBlank() || input.evidenceReference.length > 500)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        lifecycle.lock(context.workOrderId)
        usage.lockSources(listOf(input.receiptId))
        input.reworkId?.let { id ->
            val rework = reworks.get(id) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            if (rework.plan.workOrderId != context.workOrderId || input.evidenceRevision != rework.evidenceRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
            reworks.assertLive(id)
        }
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        val prior = operations.lockKey("warehouse.material.use", metadata.idempotencyKey)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.resourceId != context.workOrderId || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            val snapshot = usage.get(prior.receipt.operationId)
            snapshot.lines.forEach { locations.authorizeLocation(it.source.locationId, current, scopes.currentUnderFence(current.fence)) }
            return prior.receipt
        }
        if (input.workOrderRevision != context.workOrderRevision || input.expectedRevision != totals.useRevision(context.workOrderId))
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val previous = usage.get(input.previousUsageId)
        if (previous.workOrderId != context.workOrderId || previous.useRevision != input.expectedRevision || previous.materialMode != MaterialMode.MATERIAL_REQUIRED)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val binding = source(context, input, previous)
        val position = residuals.source(context, ResidualStockSource(input.receiptId, input.issueLineId, binding.sourceUsageId, input.stockIdentityId, input.baseUnit))
        locations.authorizeLocation(position.dimension.locationId, current, scopes.currentUnderFence(current.fence))
        val amount = input.quantityBase.toLong()
        if (amount > position.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        val remainderAmount = position.quantity - amount
        val split = input.baseUnit == WarehouseBaseUnit.MM && remainderAmount > 0
        val consumed = position.dimension.copy(stockIdentityId = if (split) UUID.randomUUID() else input.stockIdentityId,
            locationId = usage.consumedLocation(), custodianKind = if (context.customerId == null) OwnerKind.TECHNICIAN else OwnerKind.CUSTOMER,
            custodianId = context.customerId ?: current.fence.identity.userId)
        val remainder = if (remainderAmount == 0L) null else position.dimension.copy(stockIdentityId = if (split) UUID.randomUUID() else input.stockIdentityId)
        val id = UUID.randomUUID()
        val line = MaterialUsageLineSnapshot(UUID.randomUUID(), MaterialUsageSelection(input.receiptId, input.issueLineId, input.stockIdentityId, input.quantityBase, input.baseUnit),
            binding.receiptRevision, binding.issueId, binding.planLineId, binding.requestedBase, position.quantity.toString(),
            remainderAmount.toString(), position.dimension, position.revision, consumed, remainder, UUID.randomUUID())
        val time = lifecycle.now()
        val snapshot = MaterialUsageSnapshot(id, context.workOrderId, context.workOrderRevision, binding.plan.id, binding.plan.planRevision,
            Math.addExact(previous.useRevision, 1), MaterialMode.MATERIAL_REQUIRED, current.fence.identity.userId, context.customerId,
            input.evidenceReference, input.reason, null, time, UUID.nameUUIDFromBytes("warehouse:$id".toByteArray(Charsets.UTF_8)), listOf(line))
        val body = mapper.writeValueAsString(snapshot)
        val operation = PostingOperation(id, "warehouse.material.use", metadata.idempotencyKey, snapshot.actorId, context.workOrderId,
            "workorder:${context.workOrderId}", canonical.hash, "REPORT_USE", 200, body, current.fence.epoch, time)
        usage.create(snapshot, context)
        deltas.record(snapshot, input, binding.sourceUsageId)
        val unit = StockUnit.valueOf(input.baseUnit.name)
        val legs = buildList {
            add(PostingLeg(LegDirection.OUT, position.dimension, StockQuantity.of(if (split) position.quantity else amount, unit), line.id, InventoryStatus.ISSUED))
            add(PostingLeg(LegDirection.IN, consumed, StockQuantity.of(amount, unit), line.id, InventoryStatus.CONSUMED, PostingEndpoint.CONSUMED))
            if (split) add(PostingLeg(LegDirection.IN, requireNotNull(remainder), StockQuantity.of(remainderAmount, unit), line.id, InventoryStatus.ISSUED))
        }
        val splits = if (split) listOf(PostingSplit(input.stockIdentityId, position.revision, listOf(
            SegmentChild(consumed.stockIdentityId, StockQuantity.of(amount, unit), SegmentKind.CUT),
            SegmentChild(requireNotNull(remainder).stockIdentityId, StockQuantity.of(remainderAmount, unit), SegmentKind.REMNANT)))) else emptyList()
        posting.post(WarehousePost(id, 0, "POSTED", operation, MovementKind.CONSUME, input.reason, legs, splits = splits,
            facts = listOf(PostingMaterialFact(line.factId, consumed.stockIdentityId, context.customerId, context.workOrderId, "Measured material delta",
                StockQuantity.of(amount, unit), snapshot.useRevision, true, false, usageId = id)),
            usage = PostingUsage(id, context.workOrderId, context.workOrderRevision, binding.plan.id, snapshot.useRevision, body, previous.usageId)), cutover)
        operations.storeIdentity(id, canonical.json, current.fence.identity.sessionId)
        return WarehouseOperationReceipt(id, id, 1, 200, body, time)
    }

    private data class SourceBinding(val plan: MaterialPlanSnapshot, val sourceUsageId: UUID?, val receiptRevision: Long,
        val issueId: UUID, val planLineId: UUID, val requestedBase: String)

    private fun source(context: MaterialPlanningContext, input: MaterialUsageDeltaRequest, previous: MaterialUsageSnapshot): SourceBinding {
        val currentPlan = plans.current(context.workOrderId)?.plan ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val rework = reworks.get(currentPlan.id)
        if (rework == null) {
            if (input.reworkId != null || input.evidenceRevision != null) masterFailure(WarehouseErrorCode.STALE_REVISION)
            val line = previous.lines.singleOrNull { it.selection.receiptId == input.receiptId && it.selection.issueLineId == input.issueLineId }
                ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            return SourceBinding(plans.get(previous.planId).plan, previous.usageId, line.receiptRevision, line.issueId, line.planLineId, line.requestedBase)
        }
        if (input.reworkId != rework.reworkId || input.evidenceRevision != rework.evidenceRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        reworks.assertLive(rework.reworkId)
        val receipt = receipts.get(input.receiptId)
        if (receipt.issue.workOrderId != context.workOrderId || receipt.issue.customerId != context.customerId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val issued = receipt.issue.lines.singleOrNull { it.id == input.issueLineId } ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val planLine = (currentPlan.lines + rework.inheritedLines).singleOrNull { it.id == issued.planLineId }
            ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return SourceBinding(currentPlan, deltas.latestSource(input.receiptId, input.issueLineId), receipt.revision, receipt.issueId, planLine.id, planLine.quantityBase)
    }
}
