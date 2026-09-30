package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialLifecycleStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class InventoryMaterialLifecycleService(
    private val authority: CurrentAuthorityApi,
    private val store: MaterialLifecycleStore,
    private val residuals: MaterialResidualService,
    private val handovers: MaterialHandoverService,
    private val deltas: MaterialUsageDeltaService,
    private val scopes: InventoryWarehouseScopeApi,
    private val locations: WarehouseReceiptService,
    private val reworks: com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialReworkStore,
) : InventoryMaterialLifecycleApi {
    private val mapper = jacksonObjectMapper()

    override fun participates(workOrderId: java.util.UUID): Boolean = store.hasMaterials(workOrderId)

    override fun summary(context: MaterialPlanningContext): MaterialObligations {
        val current = current(context)
        val custody = store.custodyScope(context.workOrderId, current.fence.identity.userId)
        if (!current.platformAdmin && "workorder.order.view" !in current.permissions &&
            context.authority.identity.userId !in context.activeAssigneeIds && custody.isEmpty())
            masterFailure(WarehouseErrorCode.FORBIDDEN)
        store.lock(context.workOrderId)
        val summary = store.summary(context.workOrderId)
        if (current.platformAdmin || "workorder.order.view" in current.permissions) return summary
        receiptPermission(current, "workorder.order.field")
        val warehouseScope = scopes.currentUnderFence(current.fence)
        custody.map { it.second }.distinct().sortedBy(java.util.UUID::toString).forEach {
            locations.authorizeLocation(it, current, warehouseScope)
        }
        val identities = custody.map { it.first }.toSet()
        val lines = summary.lines.filter { it.issueLineId in identities }
        return summary.copy(lines = lines, outstandingBase = lines.fold(0L) { total, line ->
            Math.addExact(total, Math.addExact(line.stillAccountableBase.toLong(),
                Math.subtractExact(line.returnedBase.toLong(), line.settledReturnBase?.toLong() ?: 0)))
        }.toString())
    }

    override fun beforeChange(context: MaterialPlanningContext, change: MaterialLifecycleAction) {
        current(context)
        if (!store.hasMaterials(context.workOrderId)) return
        store.lock(context.workOrderId)
        val summary = store.summary(context.workOrderId)
        if (change == MaterialLifecycleAction.CANCEL && summary.pickedBase != "0") masterFailure(WarehouseErrorCode.MATERIAL_UNPICK_REQUIRED)
        val key = "${context.workOrderId}:${context.workOrderRevision}:$change"
        val hash = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("workOrderId" to context.workOrderId,
            "revision" to context.workOrderRevision, "action" to change))).hash
        val receipt = store.record(context, change, key, hash)
        if (change == MaterialLifecycleAction.CANCEL) store.releaseUnpicked(context, receipt.operationId)
    }

    override fun close(context: MaterialPlanningContext, request: MaterialCloseRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val current = current(context)
        receiptPermission(current, "workorder.order.close")
        receiptKey(metadata.idempotencyKey)
        if (request.reason.isBlank() || request.reason.length > 1000 || request.expectedRevision < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        store.lock(context.workOrderId)
        val hash = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request)).hash
        store.replay(context, metadata.idempotencyKey, hash)?.let { return it }
        val summary = store.summary(context.workOrderId)
        if (summary.outstandingBase != "0" || summary.reservedUnpickedBase != "0" || summary.pickedBase != "0" || reworks.hasUnissuedDemand(context.workOrderId))
            masterFailure(WarehouseErrorCode.MATERIAL_OBLIGATION_OUTSTANDING)
        if (summary.revision != request.expectedRevision || request.workOrderRevision != context.workOrderRevision || summary.materialState == ResidualSettlementState.CLOSED)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        return store.record(context, MaterialLifecycleAction.CLOSE, metadata.idempotencyKey, hash)
    }

    override fun dispatch(context: MaterialPlanningContext, request: MaterialResidualRequest, metadata: WarehouseMutationMetadata) =
        residuals.dispatch(context, request, metadata)

    override fun acknowledge(context: MaterialPlanningContext, request: MaterialResidualAcknowledgement, metadata: WarehouseMutationMetadata) =
        residuals.acknowledge(context, request, metadata)

    override fun authorizeHandover(context: MaterialPlanningContext, request: MaterialResidualRequest, metadata: WarehouseMutationMetadata) =
        handovers.authorize(context, request, metadata)

    override fun correctUse(context: MaterialPlanningContext, request: MaterialUsageDeltaRequest, metadata: WarehouseMutationMetadata) =
        deltas.append(context, request, metadata)

    private fun current(context: MaterialPlanningContext): CurrentAuthority {
        context.cutover.assertHeld()
        context.authority.assertHeld()
        return authority.lockCurrent().also {
            if (it.fence.identity != context.authority.identity || it.fence.epoch != context.authority.epoch) masterFailure(WarehouseErrorCode.STALE_AUTHORITY)
        }
    }
}
