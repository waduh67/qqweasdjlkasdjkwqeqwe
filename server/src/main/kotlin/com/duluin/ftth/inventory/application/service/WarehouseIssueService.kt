package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
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
class WarehouseIssueService(private val authority: CurrentAuthorityApi, private val scopes: InventoryWarehouseScopeApi,
    private val users: IamApi, private val masters: WarehouseMasterStore, private val receipts: WarehouseReceiptService,
    private val plans: MaterialPlanningStore, private val reservations: WarehouseReservationStore,
    private val issues: WarehouseIssueStore, private val picking: WarehouseIssuePicking, private val operations: WarehouseOperationStore,
    private val posting: WarehousePosting, private val validation: MaterialPlanValidation, private val stock: ReservationStockQueries) : InventoryIssueApi {
    private val mapper = jacksonObjectMapper()

    override fun pick(context: MaterialPlanningContext, request: WarehousePickRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val current = current(context, true)
        if (request.lines.isEmpty() || request.lines.size > 100 || request.expectedRevision < 1 || request.demandRevision < 0 ||
            request.lines.distinctBy { it.reservationId }.size != request.lines.size || request.lines.distinctBy { it.stockIdentityId }.size != request.lines.size)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val canonical = canonical(context, request)
        replay(context, "PICK", metadata, canonical, current)?.let { return it }
        val history = plans.current(context.workOrderId) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val plan = history.plan
        val demandId = history.demandDocumentId ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (history.state != "SUBMITTED" || plan.planRevision != request.expectedRevision || plan.workOrderRevision != request.workOrderRevision ||
            request.workOrderRevision != context.workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        validation.revalidate(plan.lines, current)
        if (plan.lines.any { it.substitution != null }) receiptPermission(current, "inventory.request.override")
        val preview = reservations.demand(demandId)
        val relatedDocuments = (reservations.relatedDocuments(request.lines.map { it.stockIdentityId }) + demandId).distinct()
        val rows = relatedDocuments.flatMap { reservations.rows(it) }
        authorize(rows.map { it.dimension.locationId }, current)
        reservations.lockDocuments(relatedDocuments + reservations.originDocuments(rows))
        val demand = reservations.demand(demandId)
        if (demand.revision != request.demandRevision || demand.revision != preview.revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (demand.state !in setOf("RESERVED", "PART_RESERVED", "PART_ISSUED")) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val demandLines = reservations.lines(demand, ReservationValidationMode.NEW_ALLOCATION)
        reservations.lockStock(emptyList(), rows)
        val now = reservations.now()
        val selected = picking.prepare(request, plan, demandLines, relatedDocuments.flatMap { reservations.rows(it) }, now)
        val receiver = context.activeAssigneeIds.sortedBy(UUID::toString).firstOrNull() ?: masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        val id = UUID.randomUUID()
        val snapshot = IssueSnapshot(id, "ISS-$id", 1, "PICKED", context.workOrderId, context.code, context.workOrderRevision,
            context.customerId, context.customerLabelSnapshot, demand.id, demand.revision, plan.id, plan.planRevision,
            person(current.fence.identity.userId), person(receiver), selected.lines, now)
        issues.create(snapshot, context)
        val body = mapper.writeValueAsString(snapshot)
        val operation = operation(context, "PICK", metadata, canonical, body)
        posting.post(WarehousePost(id, 0, "PICKED", operation, if (selected.legs.isEmpty()) MovementKind.RESERVE else MovementKind.TRANSFER,
            "Pick reserved WO material", selected.legs, selected.reservations, selected.splits,
            events = listOf(PostingEvent(UUID.randomUUID(), WarehouseEventKind.PICKED, body))), context.cutover)
        reservations.bind(selected.reservations, demandLines, selected.candidates, operation.id)
        issues.seal(snapshot, operation.id, body)
        supply(context, demand, metadata, canonical)
        operations.storeIdentity(operation.id, canonical.json, context.authority.identity.sessionId)
        return receipt(operation, id, 1)
    }

    override fun transition(context: MaterialPlanningContext, request: WarehouseIssueRequest, metadata: WarehouseMutationMetadata,
        dispatch: Boolean): WarehouseOperationReceipt {
        val current = current(context, true)
        if (request.reason.isBlank() || request.reason.length > 1000) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val action = if (dispatch) "DISPATCH" else "UNPICK"
        val canonical = canonical(context, request)
        replay(context, action, metadata, canonical, current)?.let { return it }
        val snapshot = issues.snapshot(request.issueId)
        if (snapshot.workOrderId != context.workOrderId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        authorize(snapshot.lines.map { it.dimension.locationId }, current)
        val rows = reservations.rows(snapshot.demandDocumentId)
        reservations.lockDocuments(listOf(snapshot.issueId, snapshot.demandDocumentId) + reservations.originDocuments(rows))
        val state = issues.state(snapshot.issueId)
        if (state.first != "PICKED" || state.second != request.expectedRevision || issues.wasUnpicked(snapshot.issueId)) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val demand = reservations.demand(snapshot.demandDocumentId)
        if (demand.revision != request.demandRevision || snapshot.planRevision != request.planRevision ||
            request.workOrderRevision != context.workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val history = plans.current(context.workOrderId) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (dispatch && (history.plan.id != snapshot.planId || snapshot.workOrderRevision != context.workOrderRevision ||
                snapshot.receiver.id !in context.activeAssigneeIds)) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (dispatch) validation.revalidate(history.plan.lines, current)
        val demandLines = reservations.lines(demand, ReservationValidationMode.BOUND_LIFECYCLE)
        reservations.lockStock(emptyList(), rows)
        val changes = snapshot.lines.map { line ->
            val row = reservations.rows(demand.id).singleOrNull { it.id == line.reservationId } ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            if (row.state != ReservationState.OPEN || row.expectedRevision != line.reservationRevision || row.dimension != line.dimension ||
                row.picked.quantityBase.toString() != line.quantityBase) masterFailure(WarehouseErrorCode.STALE_REVISION)
            if (dispatch && stock.position(row.dimension).available < 0) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            row.copy(picked = StockQuantity.of(0, row.picked.unit), unpicked = if (dispatch) row.unpicked else row.unpicked + row.picked,
                state = if (dispatch && row.unpicked.quantityBase == 0L) ReservationState.DISPATCHED else ReservationState.OPEN)
        }
        if (dispatch && !request.partial && demandLines.any { line ->
                val picked = snapshot.lines.filter { it.demandLineId == line.id }.sumOf { it.quantityBase.toLong() }
                picked != Math.subtractExact(line.requested, line.issued)
            }) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        val transit = if (dispatch) issues.transit() else null
        transit?.let { authorize(listOf(it), current) }
        val legs = if (transit == null) emptyList() else snapshot.lines.flatMap { line ->
            val quantity = StockQuantity.of(line.quantityBase.toLong(), StockUnit.valueOf(line.baseUnit.name))
            listOf(PostingLeg(LegDirection.OUT, line.dimension, quantity, line.id, InventoryStatus.AVAILABLE),
                PostingLeg(LegDirection.IN, line.dimension.copy(locationId = transit, custodianId = transit, custodianKind = OwnerKind.WAREHOUSE),
                    quantity, line.id, InventoryStatus.IN_TRANSIT))
        }
        val result = snapshot.copy(revision = Math.addExact(state.second, 1), state = if (dispatch) "DISPATCHED" else "UNPICKED",
            sender = person(context.authority.identity.userId), recordedAt = reservations.now())
        val body = mapper.writeValueAsString(result)
        val operation = operation(context, action, metadata, canonical, body)
        posting.post(WarehousePost(snapshot.issueId, state.second, if (dispatch) "DISPATCHED" else "PICKED", operation,
            if (dispatch) MovementKind.ISSUE else MovementKind.RESERVE, request.reason, legs, changes,
            events = listOf(PostingEvent(UUID.randomUUID(), if (dispatch) WarehouseEventKind.DISPATCHED else WarehouseEventKind.UNPICKED, body))), context.cutover)
        if (!dispatch) issues.unpicked(snapshot.issueId, operation.id)
        supply(context, demand, metadata, canonical)
        operations.storeIdentity(operation.id, canonical.json, context.authority.identity.sessionId)
        return receipt(operation, snapshot.issueId, result.revision)
    }

    override fun slip(context: MaterialPlanningContext, issueId: UUID): String {
        val current = current(context, false)
        val snapshot = issues.snapshot(issueId)
        if (snapshot.workOrderId != context.workOrderId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        authorize(snapshot.lines.map { it.dimension.locationId }, current)
        return issues.print(issueId)
    }

    private fun current(context: MaterialPlanningContext, mutation: Boolean): CurrentAuthority {
        context.cutover.assertHeld()
        context.authority.assertHeld()
        val current = authority.lockCurrent()
        if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        receiptPermission(current, if (mutation) "inventory.issue.manage" else "inventory.issue.view")
        receiptPermission(current, if (mutation) "inventory.request.manage" else "inventory.request.view")
        return current
    }
    private fun authorize(locations: List<UUID>, current: CurrentAuthority) {
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        locations.distinct().sortedBy(UUID::toString).forEach { receipts.authorizeLocation(it, current, scope) }
    }
    private fun replay(context: MaterialPlanningContext, action: String, metadata: WarehouseMutationMetadata,
        canonical: WarehouseCanonicalPayload, current: CurrentAuthority): WarehouseOperationReceipt? {
        receiptKey(metadata.idempotencyKey)
        val prior = operations.lockKey("warehouse.issue.${action.lowercase()}", metadata.idempotencyKey) ?: return null
        if (prior.actorId != context.authority.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.resourceId != context.workOrderId || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != context.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        val snapshot = issues.snapshot(prior.receipt.documentId)
        authorize(snapshot.lines.map { it.dimension.locationId }, current)
        reservations.lines(reservations.demand(snapshot.demandDocumentId), ReservationValidationMode.REPLAY)
        return prior.receipt
    }
    private fun supply(context: MaterialPlanningContext, demand: ReservationDemand, metadata: WarehouseMutationMetadata, canonical: WarehouseCanonicalPayload) {
        val lines = reservations.lines(demand, ReservationValidationMode.BOUND_LIFECYCLE)
        val supplies = ReservationPlanning.supplies(lines, reservations.rows(demand.id))
        val body = mapper.writeValueAsString(supplies)
        val operation = operation(context, "SUPPLY", WarehouseMutationMetadata("${metadata.idempotencyKey}:${demand.revision}"), canonical, body)
        posting.post(WarehousePost(demand.id, demand.revision, ReservationPlanning.state(supplies), operation, MovementKind.RESERVE,
            "Update issued material supply", emptyList(), events = listOf(PostingEvent(UUID.randomUUID(), WarehouseEventKind.RESERVED, body))), context.cutover)
        reservations.snapshot(operation.id, supplies)
    }
    private fun operation(context: MaterialPlanningContext, action: String, metadata: WarehouseMutationMetadata, canonical: WarehouseCanonicalPayload, body: String) =
        PostingOperation(UUID.randomUUID(), "warehouse.issue.${action.lowercase()}", metadata.idempotencyKey,
            context.authority.identity.userId, context.workOrderId, "workorder:${context.workOrderId}", canonical.hash, action, 200, body, context.authority.epoch, reservations.now())
    private fun receipt(operation: PostingOperation, document: UUID, revision: Long) = WarehouseOperationReceipt(operation.id, document, revision, 200, operation.originalBody, operation.recordedAt)
    private fun canonical(context: MaterialPlanningContext, request: Any) = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("workOrderId" to context.workOrderId, "request" to request)))
    private fun person(id: UUID): IssuePerson = users.findUser(id)?.takeIf { it.active }?.let { IssuePerson(id, it.name) }
        ?: masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
}
