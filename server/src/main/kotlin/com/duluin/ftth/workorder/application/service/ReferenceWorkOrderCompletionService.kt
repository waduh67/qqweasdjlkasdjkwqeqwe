package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.inventory.InventoryTenantCutoverApi
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseOperationClass
import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReferenceWarehouseStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseTransferStock
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.positiveReceiptQuantity
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.TransferLine
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.application.service.referenceTimestamp
import com.duluin.ftth.inventory.domain.model.*
import com.duluin.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderCompletionStore
import com.duluin.ftth.workorder.adapter.outbound.persistence.ReferenceWorkOrderStore
import com.duluin.ftth.workorder.application.port.inbound.*
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional
class ReferenceWorkOrderCompletionService(private val orders: ReferenceWorkOrderService, private val store: ReferenceWorkOrderStore,
    private val proofs: ReferenceWorkOrderEvidenceService, private val completions: ReferenceWorkOrderCompletionStore,
    private val workOrders: WorkOrderRepository, private val warehouse: ReferenceWarehouseStore, private val stock: WarehouseTransferStock,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore, private val cutovers: InventoryTenantCutoverApi,
    private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()

    fun complete(id: UUID, input: ReferenceWorkOrderCompletionInput, key: String): WarehouseOperationReceipt {
        val access = orders.access(key)
        val current = orders.authorized(store.get(id, true), access)
        orders.field(current, access)
        val canonical = orders.canonical(id, input)
        orders.replay("COMPLETE", key, canonical, access)?.let { return it }
        val prior = orders.editable(id, input.expectedRevision, access)
        orders.text(input.notes, 1000)
        if (input.materials.size > 100 || input.materials.distinctBy { it.stockIdentityId }.size != input.materials.size ||
            prior.type.materialRequired && input.materials.isEmpty()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST, "Pilih material yang dipakai")
        val photos = proofs.requireCurrent(id, prior.assignmentGeneration, prior.type.photoSlots).sortedBy { it.caption }
            .map { ReferenceCompletionPhoto(it.id, requireNotNull(it.caption), requireNotNull(it.sha256), it.sizeBytes, it.contentType) }
        val actor = access.current.fence.identity.userId
        val source = if (input.materials.isEmpty()) null else warehouse.technicianLocation(actor, prior.technicianName)
        val lines = input.materials.sortedBy { it.stockIdentityId.toString() }.map { selection ->
            val piece = stock.get(selection.stockIdentityId, requireNotNull(source))
            stock.assertUnallocated(selection.stockIdentityId)
            stock.assertTechnician(piece, actor)
            val amount = positiveReceiptQuantity(selection.quantityBase, piece.unit).quantityBase
            if (amount > piece.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (piece.tracking.name == "SERIAL" && amount != 1L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            TransferLine(UUID.randomUUID(), piece, amount)
        }
        val now = referenceTimestamp()
        val document = if (lines.isEmpty()) null else UUID.randomUUID()
        val destination = if (lines.isEmpty()) null else completions.consumedLocation()
        val legs = mutableListOf<PostingLeg>()
        val splits = mutableListOf<PostingSplit>()
        val materials = lines.map { line ->
            val piece = line.source
            val unit = StockUnit.valueOf(piece.unit.name)
            val rest = piece.quantity - line.quantity
            val split = piece.unit.name == "MM" && rest > 0
            val consumed = if (split) UUID.randomUUID() else piece.dimension.stockIdentityId
            legs += PostingLeg(LegDirection.OUT, piece.dimension, StockQuantity.of(if (split) piece.quantity else line.quantity, unit), line.id, InventoryStatus.ISSUED)
            legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = consumed, locationId = requireNotNull(destination)),
                StockQuantity.of(line.quantity, unit), line.id, InventoryStatus.CONSUMED, PostingEndpoint.CONSUMED)
            if (split) {
                val remainder = UUID.randomUUID()
                legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = remainder), StockQuantity.of(rest, unit), line.id, InventoryStatus.ISSUED)
                splits += PostingSplit(piece.dimension.stockIdentityId, piece.revision, listOf(
                    SegmentChild(consumed, StockQuantity.of(line.quantity, unit), SegmentKind.CUT),
                    SegmentChild(remainder, StockQuantity.of(rest, unit), SegmentKind.REMNANT)))
            }
            val position = warehouse.positions(piece.dimension.skuId).single { it.stockIdentityId == piece.dimension.stockIdentityId && it.locationId == source }
            ReferenceCompletionMaterial(line.id, piece.dimension.stockIdentityId, consumed, piece.dimension.skuId, position.skuName,
                line.quantity.toString(), piece.unit.name, piece.tracking.name, position.serial, position.mac)
        }
        val completion = ReferenceWorkOrderCompletionView(id, prior.revision + 1, prior.assignmentGeneration, actor, input.notes, now, photos, materials, document)
        val proof = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(completion))
        completions.save(completion, proof)
        if (document != null) {
            completions.draft(document, id, prior.customerId, requireNotNull(destination), lines, actor, access.current.fence.epoch, access.cutover.snapshot.epoch, input.notes)
            val operation = PostingOperation(UUID.randomUUID(), "warehouse.reference.consume", "complete:$id:${completion.revision}", actor, document, "reference:$document",
                canonical.hash, "CONSUME", 201, mapper.writeValueAsString(completion), access.current.fence.epoch, now)
            val command = WarehousePost(document, 0, "POSTED", operation, MovementKind.CONSUME, input.notes.ifBlank { "Material work order" }, legs, splits = splits)
            warehouse.bind(command, requireNotNull(source), destination, "CONSUME")
            posting.post(command, cutovers.lockForCommand(access.cutover.snapshot.epoch, WarehouseOperationClass.REFERENCE_STOCK))
            operations.storeIdentity(operation.id, canonical.json, access.current.fence.identity.sessionId)
        }
        val order = workOrders.findById(id) ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        order.completeReference(input.notes, proof.hash, now, actor)
        workOrders.save(order)
        entityManager.flush()
        val view = prior.copy(revision = completion.revision, state = ReferenceWorkOrderState.COMPLETED, lastActivityAt = now, blockedReason = null)
        store.save(view, false)
        return orders.record("COMPLETE", key, canonical, access, id, "WO", view.revision, view, input.notes, "workorder.order.field")
    }
}
