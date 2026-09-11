package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventoryMaterialService(private val authority: CurrentAuthorityApi, private val store: MaterialPlanningStore,
    private val templates: MaterialTemplateStore, private val commands: MaterialCommandStore, private val validation: MaterialPlanValidation,
    private val reservations: InventoryReservationApi, private val reservationStore: WarehouseReservationStore,
    private val invalidation: InventoryApprovalInvalidationApi, private val physical: MaterialPhysicalTotalsStore) : UnavailableMaterialActions() {
    private val mapper = jacksonObjectMapper()

    override fun summary(context: MaterialPlanningContext): MaterialSummary {
        current(context, "inventory.request.view")
        val history = store.current(context.workOrderId)
        val plan = history?.plan
        val demand = history?.demandDocumentId?.let { reservationStore.demand(it) }
        physical.assertBound(context.workOrderId)
        val rows = demand?.let { reservationStore.rows(it.id) }.orEmpty()
        val demandLines = demand?.let { reservationStore.lines(it, ReservationValidationMode.HISTORICAL_READ) }.orEmpty()
        if (demand != null && demandLines.size != plan?.lines?.size) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (rows.isNotEmpty()) reservations.allocations(context.workOrderId)
        val totals = plan?.lines.orEmpty().map { line ->
            val demandLine = demandLines.singleOrNull { it.planLineId == line.id }
            if (demand != null && (demandLine == null || demandLine.sku != line.sku.id || demandLine.unit.name != line.sku.baseUnit.name ||
                    demandLine.requested.toString() != line.quantityBase || demandLine.tracking != line.sku.tracking.name || demandLine.continuous != line.continuousCut))
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val allocated = rows.filter { it.documentLineId == demandLine?.id }
            val unpicked = allocated.fold(0L) { sum, row -> Math.addExact(sum, row.unpicked.quantityBase) }
            val picked = allocated.fold(0L) { sum, row -> Math.addExact(sum, row.picked.quantityBase) }
            val requested = demandLine?.requested ?: 0L
            val facts = demandLine?.let { physical.forDemand(context.workOrderId, it.id) } ?: MaterialPhysicalTotals(0, 0, 0, 0, 0)
            val backorder = Math.subtractExact(Math.subtractExact(requested, Math.addExact(unpicked, picked)), facts.issued)
            if (backorder < 0) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            MaterialLineTotals(line.id, line.sku.id, line.sku.baseUnit, requested.toString(), unpicked.toString(), picked.toString(),
                facts.issued.toString(), facts.used.toString(), facts.returned.toString(), facts.transferred.toString(), facts.disposed.toString(),
                facts.accountable.toString(), backorder.toString())
        }
        return MaterialSummary(context.workOrderId, plan?.materialMode ?: MaterialMode.MATERIAL_REQUIRED, plan?.reason,
            MaterialRevisions(context.workOrderRevision, plan?.planRevision ?: 0, physical.useRevision(context.workOrderId), 0),
            demand?.let { MaterialDemandState.valueOf(it.state) } ?: if (history?.state == "SUBMITTED") MaterialDemandState.SUBMITTED else MaterialDemandState.DRAFT,
            if (context.customerId == null) MaterialInstallationState.NOT_APPLICABLE else MaterialInstallationState.NOT_INSTALLED,
            MaterialQaState.PENDING, MaterialProvisioningState.NOT_APPLICABLE, MaterialSettlementState.OPEN, totals,
            plan, demand?.id, demand?.revision, templates.current(context.workType, context.action))
    }

    override fun history(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialPlanHistory> {
        current(context, "inventory.request.view")
        if (page.page < 0 || page.size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return store.history(context.workOrderId, page)
    }

    override fun replacePlan(context: MaterialPlanningContext, request: MaterialPlanningRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val current = current(context, "inventory.request.manage")
        revisions(context, request.expectedRevision, request.workOrderRevision)
        reason(request.reason)
        val canonical = canonical(context.workOrderId, request)
        replay(context, "PLAN", metadata, canonical)?.let { return it }
        val previous = store.current(context.workOrderId)?.plan
        if ((previous?.planRevision ?: 0) != request.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        store.assertReplaceable(context.workOrderId)
        val template = if (request.lines == null && request.materialMode == MaterialMode.MATERIAL_REQUIRED)
            templates.current(context.workType, context.action) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED) else null
        val input = request.lines ?: template?.lines?.map { MaterialPlanLine(it.sku.id, it.quantityBase, it.sku.baseUnit, it.continuousCut) }.orEmpty()
        val lines = if (request.materialMode == MaterialMode.NONE) {
            if (request.reason.isNullOrBlank() || input.isNotEmpty()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            emptyList()
        } else validation.lines(input, current, previous)
        val snapshot = MaterialPlanSnapshot(UUID.randomUUID(), context.workOrderId, context.code, context.workType, context.action,
            context.customerId, context.workOrderRevision, request.expectedRevision + 1, request.materialMode, request.reason,
            template?.id, current.fence.identity.userId, lines, store.now())
        invalidation.invalidateUnposted(store.documents(context.workOrderId), context.authority, context.cutover)
        store.insert(snapshot)
        return commands.record(context.workOrderId, "PLAN", metadata.idempotencyKey, canonical, context.authority, context.cutover,
            snapshot.id, snapshot.planRevision, mapper.writeValueAsString(snapshot))
    }

    override fun submitRequest(context: MaterialPlanningContext, request: MaterialPlanCommand, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt =
        transition(context, request, metadata, "SUBMIT")
    override fun reserve(context: MaterialPlanningContext, request: MaterialPlanCommand, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt =
        transition(context, request, metadata, "RESERVE")
    override fun release(context: MaterialPlanningContext, request: MaterialPlanCommand, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt =
        transition(context, request, metadata, "RELEASE")

    private fun transition(context: MaterialPlanningContext, request: MaterialPlanCommand, metadata: WarehouseMutationMetadata, action: String): WarehouseOperationReceipt {
        val current = current(context, "inventory.request.manage")
        receiptPermission(current, "inventory.request.view")
        revisions(context, request.expectedRevision, request.workOrderRevision)
        reason(request.reason)
        val canonical = canonical(context.workOrderId, request)
        replay(context, action, metadata, canonical)?.let { return it }
        val history = store.current(context.workOrderId) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val plan = history.plan
        if (plan.planRevision != request.expectedRevision || (action != "RELEASE" && plan.workOrderRevision != context.workOrderRevision))
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (action != "RELEASE" && plan.materialMode == MaterialMode.MATERIAL_REQUIRED) validation.revalidate(plan.lines, current)
        if (action == "SUBMIT") {
            if (history.state != "DRAFT") masterFailure(WarehouseErrorCode.STALE_REVISION)
            store.submit(plan, context)
        } else {
            val document = history.demandDocumentId ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val demand = reservationStore.demand(document)
            val allocations = if (action == "RELEASE") {
                if (request.reason.isNullOrBlank()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                reservationStore.rows(document).filter { it.state == com.duluin.ftth.inventory.application.port.outbound.ReservationState.OPEN }.map {
                    if (it.picked.quantityBase != 0L) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    ReservationAmount(it.id, requireNotNull(it.expectedRevision), it.unpicked.quantityBase.toString())
                }
            } else emptyList()
            reservations.execute(document, if (action == "RESERVE") ReservationAction.RESERVE else ReservationAction.RELEASE,
                ReservationRequest(demand.revision, context.workOrderRevision, plan.planRevision, allocations = allocations, reason = request.reason),
                WarehouseMutationMetadata("material:${metadata.idempotencyKey}"))
        }
        val result = summary(context)
        return commands.record(context.workOrderId, action, metadata.idempotencyKey, canonical, context.authority, context.cutover,
            result.demandDocumentId ?: plan.id, result.demandRevision ?: plan.planRevision, mapper.writeValueAsString(result))
    }

    private fun current(context: MaterialPlanningContext, permission: String): CurrentAuthority {
        context.cutover.assertHeld()
        context.authority.assertHeld()
        val current = authority.lockCurrent()
        if (current.fence.identity != context.authority.identity || current.fence.epoch != context.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        receiptPermission(current, permission)
        return current
    }
    private fun revisions(context: MaterialPlanningContext, plan: Long, workOrder: Long) {
        if (plan !in 0 until Long.MAX_VALUE || workOrder < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (context.workOrderRevision != workOrder) masterFailure(WarehouseErrorCode.STALE_REVISION)
    }
    private fun reason(value: String?) { if (value != null && (value.isBlank() || value.length > 1000)) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
    private fun canonical(workOrder: UUID, request: Any) = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("workOrderId" to workOrder, "request" to request)))
    private fun replay(context: MaterialPlanningContext, action: String, metadata: WarehouseMutationMetadata, canonical: WarehouseCanonicalPayload): WarehouseOperationReceipt? {
        receiptKey(metadata.idempotencyKey)
        val prior = commands.replay(context.workOrderId, action, metadata.idempotencyKey, canonical, context.authority, context.cutover) ?: return null
        if (action in setOf("RESERVE", "RELEASE")) {
            val owner = reservations.replay(prior.documentId, ReservationAction.valueOf(action), WarehouseMutationMetadata("material:${metadata.idempotencyKey}"))
            if (owner.documentRevision != prior.documentRevision) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        return prior
    }
}
