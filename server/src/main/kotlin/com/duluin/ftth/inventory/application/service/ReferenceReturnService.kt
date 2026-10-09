package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceReturnStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceWarehouseStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseTransferStock
import com.duluin.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class ReferenceReturnService(private val accessService: ReferenceRequestService, private val store: ReferenceReturnStore,
    private val warehouse: ReferenceWarehouseService, private val stock: WarehouseTransferStock,
    private val positions: ReferenceWarehouseStore, private val iam: IamApi) {

    fun submit(input: ReferenceReturnInput, key: String): WarehouseOperationReceipt {
        val access = accessService.access(key)
        accessService.permission(access.current, "warehouse.return.own")
        receiptText(input.reason, 1000)
        val destination = accessService.warehouse(input.warehouseId, access)
        val canonical = accessService.canonical(null, input)
        replay("SUBMIT", key, canonical, access)?.let { return it }
        if (input.lines.size !in 1..100 || input.lines.distinctBy { it.stockIdentityId }.size != input.lines.size)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val technician = iam.findUser(access.current.fence.identity.userId)?.takeIf { it.active && it.technician }
            ?: masterFailure(WarehouseErrorCode.FORBIDDEN)
        val owned = positions.positions(input.skuId).filter { it.holderKind == "TECHNICIAN" && it.holderId == technician.id && it.status == "ISSUED" }
            .associateBy { it.stockIdentityId }
        var total = 0L
        val sources = mutableSetOf<UUID>()
        val lines = input.lines.map { selection ->
            val position = owned[selection.stockIdentityId] ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            val piece = stock.get(selection.stockIdentityId, position.locationId)
            stock.assertUnallocated(selection.stockIdentityId)
            stock.assertTechnician(piece, technician.id)
            val amount = positiveReceiptQuantity(selection.quantityBase, piece.unit).quantityBase
            if (piece.tracking == WarehouseTracking.SERIAL && amount != 1L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            if (amount > piece.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (amount > Long.MAX_VALUE - total) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            total += amount
            sources += position.locationId
            ReferenceReturnLine(selection.stockIdentityId, selection.quantityBase, position.serial, position.mac)
        }
        if (sources.size != 1) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val sku = accessService.sku(input.skuId, owned.getValue(lines.first().stockIdentityId).baseUnit)
        val now = referenceTimestamp()
        val view = ReferenceReturnView(UUID.randomUUID(), 0, ReferenceReturnState.PENDING, technician.id, technician.name,
            sources.single(), destination.id, destination.name ?: destination.code, sku.id, sku.name, sku.baseUnit, sku.tracking,
            total.toString(), lines, input.reason, now, now)
        store.save(view, true)
        return record("SUBMIT", key, canonical, access, view, input.reason, "warehouse.return.own")
    }

    fun decide(id: UUID, input: ReferenceReturnDecision, key: String): WarehouseOperationReceipt {
        val access = accessService.access(key)
        accessService.permission(access.current, "warehouse.return.manage")
        accessService.notes(input.notes)
        if (!input.received) receiptText(input.notes, 1000)
        val canonical = accessService.canonical(id, input)
        replay("DECIDE", key, canonical, access)?.let { return it }
        val prior = authorized(store.get(id, true), access)
        if (prior.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (prior.state != ReferenceReturnState.PENDING) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val actor = iam.findUser(access.current.fence.identity.userId) ?: masterFailure(WarehouseErrorCode.FORBIDDEN)
        val movement = if (input.received) warehouse.receiveReturn(ReferenceTechnicianReturnInput(prior.sourceLocationId, prior.warehouseId,
            prior.technicianId, prior.skuId, prior.lines.map { ReferenceTransferLine(it.stockIdentityId, it.quantityBase) }, input.notes), "return:$id:1").documentId else null
        val view = prior.copy(revision = 1, state = if (input.received) ReferenceReturnState.RECEIVED else ReferenceReturnState.REJECTED,
            updatedAt = referenceTimestamp(), reviewedBy = actor.id, reviewerName = actor.name, reviewNotes = input.notes, movementId = movement)
        store.save(view, false)
        return record("DECIDE", key, canonical, access, view, input.notes, "warehouse.return.manage")
    }

    fun detail(id: UUID): ReferenceReturnDetail {
        val access = accessService.readAccess()
        return ReferenceReturnDetail(authorized(store.get(id), access), store.timeline(id))
    }

    fun list(page: Int, size: Int, state: ReferenceReturnState?, search: String?): WarehousePage<ReferenceReturnView> {
        if (page < 0 || size !in 1..100 || search != null && search.length > 200) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val access = accessService.readAccess()
        val managing = manages(access)
        if (!managing) accessService.permission(access.current, "warehouse.return.own")
        return store.list(page, size, state, search, if (managing) null else access.current.fence.identity.userId,
            if (managing) accessService.visibleWarehouses(access) else null)
    }

    private fun manages(access: ReferenceRequestService.Access) = access.current.platformAdmin || "warehouse.return.manage" in access.current.permissions
    private fun authorized(view: ReferenceReturnView, access: ReferenceRequestService.Access): ReferenceReturnView {
        if (manages(access)) accessService.warehouse(view.warehouseId, access)
        else {
            accessService.permission(access.current, "warehouse.return.own")
            if (view.technicianId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        }
        return view
    }
    private fun replay(action: String, key: String, canonical: WarehouseCanonicalPayload, access: ReferenceRequestService.Access): WarehouseOperationReceipt? {
        val prior = store.lockKey(action, key) ?: return null
        accessService.permission(access.current, prior.permission)
        if (prior.actorId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.epoch != access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        authorized(store.get(prior.resourceId), access)
        return prior.receipt
    }
    private fun record(action: String, key: String, canonical: WarehouseCanonicalPayload, access: ReferenceRequestService.Access,
        view: ReferenceReturnView, notes: String, permission: String) = store.command(action, key, view, access.current.fence.identity.userId,
        access.current.fence.epoch, access.cutover.snapshot.epoch, canonical, notes, permission)
}
