package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialPhysicalTotalsStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialPlanningStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialReceiptStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialUsageStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class InventoryMaterialUsageService(
    private val authority: CurrentAuthorityApi,
    private val cutovers: InventoryTenantCutoverApi,
    private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore,
    private val locations: WarehouseReceiptService,
    private val receipts: MaterialReceiptStore,
    private val plans: MaterialPlanningStore,
    private val totals: MaterialPhysicalTotalsStore,
    private val store: MaterialUsageStore,
    private val operations: WarehouseOperationStore,
    private val preparation: MaterialUsagePreparation,
    private val posting: WarehousePosting,
) : InventoryMaterialUsageApi {
    private val mapper = jacksonObjectMapper()

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
    override fun reportUse(context: MaterialPlanningContext, request: MaterialUsageRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        context.cutover.assertHeld()
        context.authority.assertHeld()
        val current = authority.lockCurrent()
        if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch)
            masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        receiptPermission(current, "workorder.order.field")
        val actor = current.fence.identity.userId
        if (actor !in context.activeAssigneeIds) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        receiptKey(metadata.idempotencyKey)
        if (request.expectedRevision !in 0 until Long.MAX_VALUE || request.planRevision <= 0 || request.workOrderRevision < 0 ||
            request.evidenceReference.isBlank() || request.evidenceReference.length > 500 || request.lines.size > 100 ||
            request.lines.distinctBy { it.receiptId to it.issueLineId }.size != request.lines.size ||
            request.lines.distinctBy { it.stockIdentityId }.size != request.lines.size ||
            request.reason?.let { it.isBlank() || it.length > 1000 } == true ||
            request.networkReferenceLabel?.let { it.isBlank() || it.length > 500 } == true)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        val prior = operations.lockKey("warehouse.material.use", metadata.idempotencyKey)
        if (prior != null) {
            if (prior.actorId != actor) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.resourceId != context.workOrderId || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != context.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            authorize(store.get(prior.receipt.operationId), current)
            return prior.receipt
        }
        if (request.workOrderRevision != context.workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val existing = totals.useRevision(context.workOrderId)
        if (request.expectedRevision != existing) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (existing != 0L) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Usage is immutable; an explicit corrective command is required")
        val plan = plans.current(context.workOrderId)?.takeIf { it.state == "SUBMITTED" }?.plan
            ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (plan.planRevision != request.planRevision || plan.workOrderRevision > context.workOrderRevision ||
            plan.customerId != context.customerId || plan.workType != context.workType || plan.action != context.action)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (plan.materialMode != request.materialMode) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val id = UUID.randomUUID()
        val prepared = when (request.materialMode) {
            MaterialMode.NONE -> {
                if (request.lines.isNotEmpty() || request.reason.isNullOrBlank()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                emptyList()
            }
            MaterialMode.MATERIAL_REQUIRED -> {
                if (request.lines.isEmpty()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                masters.lockTopology()
                store.lockSources(request.lines.map { it.receiptId })
                val sources = request.lines.map { input ->
                    val receipt = receipts.get(input.receiptId)
                    if (receipt.receiver.id != actor) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
                    if (receipt.issue.workOrderId != context.workOrderId || receipt.issue.customerId != context.customerId || receipt.issue.planId != plan.id)
                        masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    val issued = receipt.issue.lines.singleOrNull { it.id == input.issueLineId } ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    val planLine = plan.lines.singleOrNull { it.id == issued.planLineId } ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    MaterialUsageSource(receipt, input, planLine)
                }
                authorizeLocations(sources.flatMap { source -> source.receipt.lines.mapNotNull { it.accepted?.locationId } }, current)
                val destination = MaterialUsageDestination(store.consumedLocation(), context.customerId, id)
                sources.map { preparation.prepare(it, destination) }
            }
        }
        val recordedAt = plans.now()
        val postingId = if (prepared.isEmpty()) null else UUID.nameUUIDFromBytes("warehouse:$id".toByteArray(Charsets.UTF_8))
        val snapshot = MaterialUsageSnapshot(id, context.workOrderId, context.workOrderRevision, plan.id, plan.planRevision, 1,
            request.materialMode, actor, context.customerId, request.evidenceReference, request.reason, request.networkReferenceLabel,
            recordedAt, postingId, prepared.map { it.line })
        val body = mapper.writeValueAsString(snapshot)
        val operation = PostingOperation(id, "warehouse.material.use", metadata.idempotencyKey, actor, context.workOrderId,
            "workorder:${context.workOrderId}", canonical.hash, "REPORT_USE", 200, body, current.fence.epoch, recordedAt)
        store.create(snapshot, context)
        when (request.materialMode) {
            MaterialMode.NONE -> store.recordNone(snapshot, operation, context)
            MaterialMode.MATERIAL_REQUIRED -> posting.post(WarehousePost(id, 0, "POSTED", operation, MovementKind.CONSUME,
                "Measured acknowledged material use", prepared.flatMap { it.legs }, splits = prepared.mapNotNull { it.split },
                facts = prepared.map { it.fact }, usage = PostingUsage(id, context.workOrderId, context.workOrderRevision, plan.id, 1, body)), context.cutover)
        }
        operations.storeIdentity(id, canonical.json, current.fence.identity.sessionId)
        return WarehouseOperationReceipt(id, id, 1, 200, body, recordedAt)
    }

    @Transactional(timeout = 30)
    override fun usage(id: UUID): String {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        authorize(store.get(id), current)
        return store.body(id)
    }

    private fun authorize(snapshot: MaterialUsageSnapshot, current: CurrentAuthority) {
        receiptPermission(current, "workorder.order.field")
        if (snapshot.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        authorizeLocations(snapshot.lines.map { it.source.locationId }, current)
    }

    private fun authorizeLocations(ids: List<UUID>, current: CurrentAuthority) {
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        ids.distinct().sortedBy(UUID::toString).forEach { locations.authorizeLocation(it, current, scope) }
    }
}
