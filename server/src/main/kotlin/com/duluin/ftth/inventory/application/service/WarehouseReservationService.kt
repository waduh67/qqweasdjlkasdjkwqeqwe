package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReservationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReservationValidationMode
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReservationStockQueries
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.network.SiteReferenceApi
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Service
class WarehouseReservationService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val workOrders: InventoryReservationWorkOrderPort,
    private val masters: WarehouseMasterStore, private val receipts: WarehouseReceiptService,
    private val store: WarehouseReservationStore, private val operations: WarehouseOperationStore, private val posting: WarehousePosting,
    private val planning: ReservationAllocationPlanning, private val stock: ReservationStockQueries, private val sites: SiteReferenceApi) : InventoryReservationApi {
    private val mapper = jacksonObjectMapper()

    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    override fun replay(documentId: UUID, action: ReservationAction, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        receiptKey(metadata.idempotencyKey)
        val prior = operations.findKey("warehouse.reservation.${action.name.lowercase()}", metadata.idempotencyKey)
            ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (prior.resourceId != documentId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val canonical = mapper.readTree(operations.identity(prior.receipt.operationId))
        val request = mapper.treeToValue(canonical.path("request"), ReservationRequest::class.java)
        return execute(documentId, action, request, metadata)
    }

    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    override fun execute(documentId: UUID, action: ReservationAction, request: ReservationRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        validate(action, request)
        receiptKey(metadata.idempotencyKey)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.request.manage")
        if (action in setOf(ReservationAction.PICK, ReservationAction.UNPICK)) receiptPermission(current, "inventory.issue.manage")
        if (action in setOf(ReservationAction.EXTEND, ReservationAction.REALLOCATE)) receiptPermission(current, "workorder.order.assign")
        if (request.lines.any { it.stockIdentityId != null }) receiptPermission(current, "inventory.request.override")
        val preview = store.demand(documentId)
        val targetPreview = request.target?.let { store.demand(it.documentId) }
        val contexts = (listOf(preview) + listOfNotNull(targetPreview)).distinctBy { it.workOrder }.sortedBy { it.workOrder.toString() }
            .associate { it.workOrder to workOrders.lock(it.workOrder, current, null) }
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        val rows = store.rows(documentId)
        val targetRows = targetPreview?.let { store.rows(it.id) }.orEmpty()
        (rows + targetRows).map { it.dimension.locationId }.distinct().forEach { receipts.authorizeLocation(it, current, scope) }
        val lines = store.lines(preview, ReservationValidationMode.REPLAY)
        val targetLines = targetPreview?.let { store.lines(it, ReservationValidationMode.REPLAY) }.orEmpty()
        val boundDocuments = listOf(documentId) + listOfNotNull(targetPreview?.id) + store.originDocuments(rows + targetRows)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("documentId" to documentId, "action" to action, "request" to request)))
        val namespace = "warehouse.reservation.${action.name.lowercase()}"
        val prior = operations.lockKey(namespace, metadata.idempotencyKey)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.hash != canonical.hash || prior.resourceId != documentId) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            store.lockDocuments(boundDocuments)
            return prior.receipt
        }
        checkDemand(preview, request.expectedRevision, request.planRevision, contexts.getValue(preview.workOrder), request.workOrderRevision, action)
        store.lines(preview, if (action == ReservationAction.RESERVE) ReservationValidationMode.NEW_ALLOCATION else ReservationValidationMode.BOUND_LIFECYCLE)
        if (targetPreview != null) {
            val input = requireNotNull(request.target)
            if (targetPreview.workOrder == preview.workOrder) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            checkDemand(targetPreview, input.expectedRevision, input.planRevision, contexts.getValue(targetPreview.workOrder), input.workOrderRevision, ReservationAction.RESERVE)
            store.lines(targetPreview, ReservationValidationMode.NEW_ALLOCATION)
        }
        val now = store.now()
        val context = contexts.getValue(preview.workOrder)
        val expiry = maxOf(preview.submittedAt.plusSeconds(86400), (context.scheduledEndAt ?: context.scheduledAt ?: preview.submittedAt).plusSeconds(86400))
        val plan = if (action == ReservationAction.RESERVE) {
            if (expiry <= now) masterFailure(WarehouseErrorCode.STALE_REVISION)
            val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
            planning.prepare(lines, request.lines, rows, expiry, WarehouseQueryAccess(
                if (current.platformAdmin) AuthorityScope.Unrestricted else scope, areas, sites.visibleAreas(areas), false, false))
        } else null
        val candidates = plan?.candidates.orEmpty()
        candidates.map { it.dimension.locationId }.distinct().forEach { receipts.authorizeLocation(it, current, scope) }
        store.lockDocuments(boundDocuments + candidates.map { it.originDocument })
        val document = store.demand(documentId)
        if (document.revision != preview.revision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val target = targetPreview?.let { store.demand(it.id).also { locked -> if (locked.revision != it.revision) masterFailure(WarehouseErrorCode.STALE_REVISION) } }
        store.lockStock(candidates, rows + targetRows)
        val liveRows = store.rows(documentId)
        val locked = plan?.let { planning.refresh(it, liveRows) }.orEmpty()
        val changes = plan?.changes ?: transition(action, request, liveRows, now)
        if (action == ReservationAction.REALLOCATE) {
            val targetDocument = requireNotNull(target)
            val targetInput = requireNotNull(request.target)
            val targetLine = targetLines.singleOrNull { it.id == targetInput.demandLineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (changes.size != 1) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val source = liveRows.single { it.id == changes.single().id }
            if (source.dimension.skuId != targetLine.sku || source.unpicked.unit != targetLine.unit) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val available = stock.position(source.dimension)
            val targetContext = contexts.getValue(targetDocument.workOrder)
            val targetExpiry = maxOf(targetDocument.submittedAt.plusSeconds(86400), (targetContext.scheduledEndAt ?: targetContext.scheduledAt ?: targetDocument.submittedAt).plusSeconds(86400))
            if (targetExpiry <= now) masterFailure(WarehouseErrorCode.STALE_REVISION)
            val proposed = ReservationPlanning.allocate(targetLine, ReservationSelection(targetLine.id, source.unpicked.quantityBase.toString()),
                listOf(available.copy(available = Math.addExact(available.available, source.unpicked.quantityBase))), targetRows, targetExpiry)
            val added = proposed.sumOf { row -> row.unpicked.quantityBase - (targetRows.singleOrNull { it.id == row.id }?.unpicked?.quantityBase ?: 0) }
            if (added != source.unpicked.quantityBase) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            val sourceReceipt = persist(document, action, changes, lines, liveRows, locked, cutover, current.fence.identity.userId,
                current.fence.epoch, namespace, metadata.idempotencyKey, canonical, request.reason, now, current.fence.identity.sessionId)
            persist(targetDocument, ReservationAction.RESERVE, proposed, targetLines, targetRows, listOf(available), cutover, current.fence.identity.userId,
                current.fence.epoch, "$namespace.target", metadata.idempotencyKey, canonical, request.reason, now, current.fence.identity.sessionId)
            return sourceReceipt
        }
        return persist(document, action, changes, lines, liveRows, locked, cutover, current.fence.identity.userId,
            current.fence.epoch, namespace, metadata.idempotencyKey, canonical, request.reason, now, current.fence.identity.sessionId)
    }

    @Transactional(timeout = 30)
    override fun allocations(workOrderId: UUID): List<ReservationAllocation> {
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.request.view")
        workOrders.lock(workOrderId, current, null)
        val documents = store.documents(workOrderId)
        if (documents.isEmpty() || documents.size > 100) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        store.lockDocuments(documents)
        val scope = scopes.currentUnderFence(current.fence)
        return documents.flatMap { id ->
            val demand = store.demand(id)
            store.lines(demand, ReservationValidationMode.HISTORICAL_READ)
            store.rows(id).map { it.dimension.locationId }.distinct().forEach { receipts.authorizeLocation(it, current, scope) }
            store.allocations(demand)
        }
    }

    internal fun persist(document: ReservationDemand, action: ReservationAction, changes: List<ReservationChange>, lines: List<ReservationDemandLine>,
        rows: List<ReservationChange>, candidates: List<ReservationCandidate>, cutover: TenantCutoverFence, actor: UUID, authorityEpoch: Long,
        namespace: String, key: String, canonical: WarehouseCanonicalPayload, reason: String?, now: Instant, session: String?,
        event: WarehouseEventKind? = null): WarehouseOperationReceipt {
        val supplies = ReservationPlanning.supplies(lines, rows.filter { old -> changes.none { it.id == old.id } } + changes)
        val state = ReservationPlanning.state(supplies)
        val id = UUID.randomUUID()
        val revision = Math.addExact(document.revision, 1)
        val body = mapper.writeValueAsString(mapOf("documentId" to document.id, "revision" to revision, "state" to state,
            "operationId" to id, "lines" to supplies, "shortage" to supplies.any { it.backorderBase != "0" }, "reservations" to changes))
        val operation = PostingOperation(id, namespace, key, actor, document.id, "demand:${document.id}", canonical.hash, action.name, 200, body, authorityEpoch, now)
        val kind = if (action in setOf(ReservationAction.RELEASE, ReservationAction.REALLOCATE)) MovementKind.RELEASE else MovementKind.RESERVE
        val eventKind = event ?: when (action) {
            ReservationAction.PICK -> WarehouseEventKind.PICKED
            ReservationAction.UNPICK -> WarehouseEventKind.UNPICKED
            ReservationAction.RELEASE, ReservationAction.REALLOCATE -> WarehouseEventKind.RELEASED
            else -> WarehouseEventKind.RESERVED
        }
        posting.post(WarehousePost(document.id, document.revision, state, operation, kind, reason ?: action.name, emptyList(), changes,
            events = listOf(PostingEvent(UUID.randomUUID(), eventKind, body))), cutover)
        store.bind(changes, lines, candidates, id)
        store.snapshot(id, supplies)
        operations.storeIdentity(id, canonical.json, session)
        return WarehouseOperationReceipt(id, document.id, revision, 200, body, now)
    }

    private fun transition(action: ReservationAction, request: ReservationRequest, rows: List<ReservationChange>, now: Instant) = request.allocations.map { input ->
        val row = rows.singleOrNull { it.id == input.reservationId } ?: masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        if (row.expectedRevision != input.expectedRevision || row.state != ReservationState.OPEN) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val quantity = StockQuantity.of(ReservationPlanning.quantity(input.quantityBase), row.unpicked.unit)
        when (action) {
            ReservationAction.RELEASE, ReservationAction.REALLOCATE -> {
                if (row.picked.quantityBase != 0L || row.unpicked != quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
                row.copy(unpicked = StockQuantity.of(0, row.unpicked.unit), state = ReservationState.RELEASED)
            }
            ReservationAction.PICK -> {
                if (quantity.quantityBase > row.unpicked.quantityBase || row.expiresAt <= now) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
                row.copy(unpicked = row.unpicked - quantity, picked = row.picked + quantity)
            }
            ReservationAction.UNPICK -> {
                if (quantity.quantityBase > row.picked.quantityBase) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
                row.copy(unpicked = row.unpicked + quantity, picked = row.picked - quantity)
            }
            ReservationAction.EXTEND -> {
                val expiry = requireNotNull(request.expiresAt)
                if (expiry <= maxOf(now, row.expiresAt) || quantity != row.unpicked + row.picked) masterFailure(WarehouseErrorCode.STALE_REVISION)
                row.copy(expiresAt = expiry)
            }
            ReservationAction.RESERVE -> masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        }
    }

    private fun checkDemand(document: ReservationDemand, revision: Long, plan: Long, workOrder: ReservationWorkOrder, workOrderRevision: Long, action: ReservationAction) {
        if (document.revision != revision || document.planRevision != plan || workOrder.revision != workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (document.state !in setOf("SUBMITTED", "PART_RESERVED", "RESERVED", "PART_ISSUED") || (!workOrder.active && action !in setOf(ReservationAction.RELEASE, ReservationAction.UNPICK)))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    private fun validate(action: ReservationAction, request: ReservationRequest) {
        if (request.expectedRevision !in 0 until Long.MAX_VALUE || request.workOrderRevision < 0 || request.planRevision < 1 ||
            request.lines.size > 100 || request.allocations.size > 100 || request.lines.distinctBy { it.demandLineId }.size != request.lines.size ||
            request.allocations.distinctBy { it.reservationId }.size != request.allocations.size ||
            (action == ReservationAction.RESERVE && request.allocations.isNotEmpty()) || (action != ReservationAction.RESERVE && (request.allocations.isEmpty() || request.lines.isNotEmpty())) ||
            (action == ReservationAction.EXTEND) != (request.expiresAt != null) || (action == ReservationAction.REALLOCATE) != (request.target != null) ||
            (request.reason?.length ?: 0) > 1000 || ((action != ReservationAction.RESERVE || request.lines.any { it.stockIdentityId != null }) && request.reason.isNullOrBlank()))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
