package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialReceiptStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseIssueStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReservationStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class InventoryMaterialReceiptService(
    private val authority: CurrentAuthorityApi,
    private val cutovers: InventoryTenantCutoverApi,
    private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore,
    private val locations: WarehouseReceiptService,
    private val users: IamApi,
    private val issues: WarehouseIssueStore,
    private val store: MaterialReceiptStore,
    private val reservations: WarehouseReservationStore,
    private val operations: WarehouseOperationStore,
    private val preparation: MaterialReceiptPreparation,
    private val posting: WarehousePosting,
) : InventoryMaterialReceiptApi {
    private val mapper = jacksonObjectMapper()

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
    override fun acknowledge(context: MaterialPlanningContext, request: MaterialReceiptRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        context.cutover.assertHeld()
        context.authority.assertHeld()
        val current = authority.lockCurrent()
        if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch)
            masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        receiptPermission(current, "workorder.order.field")
        val actor = current.fence.identity.userId
        if (actor !in context.activeAssigneeIds) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        receiptKey(metadata.idempotencyKey)
        if (request.expectedRevision !in 2 until Long.MAX_VALUE || request.evidenceReference.isBlank() || request.evidenceReference.length > 500 ||
            request.lines.isEmpty() || request.lines.size > 100 || request.lines.distinctBy { it.issueLineId }.size != request.lines.size)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val issue = issues.snapshot(request.issueId)
        if (issue.workOrderId != context.workOrderId || issue.customerId != context.customerId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (issue.receiver.id != actor) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        val prior = operations.lockKey("warehouse.material.acknowledge", metadata.idempotencyKey)
        if (prior != null) {
            if (prior.actorId != actor) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.resourceId != request.issueId || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != context.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            authorizeReceipt(store.get(prior.receipt.operationId), current)
            return prior.receipt
        }
        if (issue.workOrderRevision != context.workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val dispatch = issues.dispatchDestinations(issue.issueId)
        if (dispatch.size != issue.lines.size) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        masters.lockTopology()
        val destination = store.destination(actor)
        authorize(dispatch.map { it.locationId } + destination, current)
        val rows = reservations.rows(issue.demandDocumentId)
        reservations.lockDocuments(listOf(issue.issueId, issue.demandDocumentId) + reservations.originDocuments(rows))
        val state = issues.state(issue.issueId)
        if (state.second != request.expectedRevision || state.first !in setOf("DISPATCHED", "PART_RECEIVED"))
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val previous = store.accepted(issue.issueId)
        val prepared = request.lines.map { input ->
            val line = issue.lines.singleOrNull { it.id == input.issueLineId } ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val transit = dispatch.singleOrNull { it.stockIdentityId == line.dimension.stockIdentityId }
                ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            preparation.prepare(input, MaterialReceiptSource(line, transit, previous[line.id] ?: 0), MaterialReceiptDestination(actor, destination))
        }
        if (prepared.all { it.line.accepted == null }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val totals = issue.lines.map { line ->
            val accepted = Math.addExact(previous[line.id] ?: 0, prepared.singleOrNull { it.line.selection.issueLineId == line.id }?.line?.selection?.acceptedBase?.toLong() ?: 0)
            MaterialReceiptTotal(line.id, line.baseUnit, line.quantityBase, accepted.toString(), Math.subtractExact(line.quantityBase.toLong(), accepted).toString())
        }
        val receiver = users.findUser(actor)?.takeIf { it.active && it.technician } ?: masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        val operationId = UUID.randomUUID()
        val recordedAt = reservations.now()
        val nextState = if (totals.all { it.inTransitBase == "0" }) "RECEIVED" else "PART_RECEIVED"
        val postingId = UUID.nameUUIDFromBytes("warehouse:$operationId".toByteArray(Charsets.UTF_8))
        val snapshot = MaterialReceiptSnapshot(operationId, issue.issueId, Math.addExact(state.second, 1), nextState,
            IssuePerson(actor, receiver.name), request.evidenceReference, recordedAt, postingId, issue, prepared.map { it.line }, totals)
        val body = mapper.writeValueAsString(snapshot)
        val operation = PostingOperation(operationId, "warehouse.material.acknowledge", metadata.idempotencyKey, actor,
            issue.issueId, "workorder:${issue.workOrderId}", canonical.hash, "ACKNOWLEDGE", 200, body, current.fence.epoch, recordedAt)
        posting.post(WarehousePost(issue.issueId, state.second, nextState, operation, MovementKind.TRANSFER,
            "Receiver acknowledged issued material", prepared.flatMap { it.legs }, splits = prepared.mapNotNull { it.split },
            events = listOf(PostingEvent(UUID.randomUUID(), WarehouseEventKind.ACKNOWLEDGED, body))), context.cutover)
        store.save(snapshot, body)
        operations.storeIdentity(operationId, canonical.json, current.fence.identity.sessionId)
        return WarehouseOperationReceipt(operationId, issue.issueId, snapshot.revision, 200, body, recordedAt)
    }

    @Transactional(timeout = 30)
    override fun receipt(id: UUID): String {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        authorizeReceipt(store.get(id), current)
        return store.body(id)
    }

    private fun authorizeReceipt(receipt: MaterialReceiptSnapshot, current: CurrentAuthority) {
        receiptPermission(current, "workorder.order.field")
        if (receipt.receiver.id != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        authorize(receipt.lines.flatMap { listOfNotNull(it.source.locationId, it.accepted?.locationId) }, current)
    }

    private fun authorize(ids: List<UUID>, current: CurrentAuthority) {
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        ids.distinct().sortedBy(UUID::toString).forEach { locations.authorizeLocation(it, current, scope) }
    }
}
