package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceRequestStore
import com.duluin.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class ReferenceRequestMovementService(private val requests: ReferenceRequestService, private val store: ReferenceRequestStore,
    private val stock: ReferenceWarehouseService) {
    fun receive(id: UUID, input: ReferenceRequestReceipt, key: String): WarehouseOperationReceipt {
        val access = requests.access(key)
        requests.permission(access.current, "warehouse.request.receive")
        requests.notes(input.notes)
        requests.warehouse(input.warehouseId, access)
        val canonical = requests.canonical(id, input)
        requests.replay("RECEIVE", key, canonical, access)?.let { return it }
        val prior = requests.editable(id, input.expectedRevision, access)
        movable(prior)
        if (prior.kind != ReferenceRequestKind.PROCUREMENT || prior.warehouseId != null && prior.warehouseId != input.warehouseId)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val line = prior.lines.singleOrNull { it.id == input.lineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val quantity = positiveReceiptQuantity(input.quantityBase, line.baseUnit).quantityBase
        if (quantity > line.approvedBase.toLong() - line.receivedBase.toLong())
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Penerimaan melebihi sisa persetujuan")
        val sku = requests.sku(line.skuId ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST), line.baseUnit)
        val movement = stock.receiveForRequest(ReferenceReceiptInput(input.warehouseId, listOf(ReceiptLineInput(sku.id, input.quantityBase,
            input.serials, input.lotCode, input.conversion, input.cost)), input.notes, input.supplierId, input.reference), movementKey(prior, "RECEIVE"))
        val changed = line.copy(receivedBase = Math.addExact(line.receivedBase.toLong(), quantity).toString(),
            fulfilledBase = if (prior.warehouseId != null) Math.addExact(line.fulfilledBase.toLong(), quantity).toString() else line.fulfilledBase)
        return finish("RECEIVE", key, canonical, access, prior, changed, input.notes, "warehouse.request.receive", movement.documentId)
    }

    fun handover(id: UUID, input: ReferenceRequestHandover, key: String): WarehouseOperationReceipt {
        val access = requests.access(key)
        requests.permission(access.current, "warehouse.request.handover")
        requests.notes(input.notes)
        requests.warehouse(input.warehouseId, access)
        val canonical = requests.canonical(id, input)
        requests.replay("HANDOVER", key, canonical, access)?.let { return it }
        val prior = requests.editable(id, input.expectedRevision, access)
        movable(prior)
        val technician = prior.technicianId ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val line = prior.lines.singleOrNull { it.id == input.lineId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (input.lines.size !in 1..100 || input.lines.distinctBy { it.stockIdentityId }.size != input.lines.size)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val quantity = input.lines.fold(0L) { total, selection ->
            val selected = positiveReceiptQuantity(selection.quantityBase, line.baseUnit).quantityBase
            if (selected > Long.MAX_VALUE - total) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            total + selected
        }
        if (quantity > line.approvedBase.toLong() - line.fulfilledBase.toLong() ||
            prior.kind == ReferenceRequestKind.PROCUREMENT && quantity > line.receivedBase.toLong() - line.fulfilledBase.toLong())
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Serah terima melebihi material yang tersedia untuk pengajuan")
        val sku = requests.sku(line.skuId ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST), line.baseUnit)
        val movement = stock.handover(ReferenceTechnicianHandoverInput(input.warehouseId, technician, sku.id, input.lines, input.notes), movementKey(prior, "HANDOVER"))
        val changed = line.copy(fulfilledBase = Math.addExact(line.fulfilledBase.toLong(), quantity).toString())
        return finish("HANDOVER", key, canonical, access, prior, changed, input.notes, "warehouse.request.handover", movement.documentId)
    }

    private fun movable(view: ReferenceRequestView) {
        if (view.state !in setOf(ReferenceRequestState.APPROVED, ReferenceRequestState.PARTIALLY_RECEIVED,
                ReferenceRequestState.RECEIVED, ReferenceRequestState.PARTIALLY_FULFILLED))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Pengajuan belum disetujui atau sudah selesai")
    }
    private fun movementKey(view: ReferenceRequestView, action: String) = "request:${view.id}:${view.revision + 1}:${action.lowercase()}"
    private fun finish(action: String, key: String, canonical: WarehouseCanonicalPayload, access: ReferenceRequestService.Access,
        prior: ReferenceRequestView, changed: ReferenceRequestLineView, notes: String, permission: String, movement: UUID): WarehouseOperationReceipt {
        val lines = prior.lines.map { if (it.id == changed.id) changed else it }
        val state = when {
            lines.all { it.fulfilledBase.toLong() == it.approvedBase.toLong() } -> ReferenceRequestState.FULFILLED
            prior.technicianId != null && lines.any { it.fulfilledBase.toLong() > 0 } -> ReferenceRequestState.PARTIALLY_FULFILLED
            prior.kind == ReferenceRequestKind.PROCUREMENT -> if (lines.any { it.receivedBase.toLong() < it.approvedBase.toLong() })
                ReferenceRequestState.PARTIALLY_RECEIVED else ReferenceRequestState.RECEIVED
            else -> ReferenceRequestState.PARTIALLY_FULFILLED
        }
        val view = prior.copy(revision = prior.revision + 1, state = state, lines = lines, updatedAt = Instant.now())
        store.save(view, false)
        return requests.record(action, key, canonical, access, view, notes, permission, movement)
    }
}
