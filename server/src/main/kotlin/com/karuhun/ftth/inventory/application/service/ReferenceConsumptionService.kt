package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.common.warehouse.warehouseFailure
import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.adapter.outbound.persistence.ReferenceConsumptionStore
import com.karuhun.ftth.inventory.adapter.outbound.persistence.ReferenceWarehouseStore
import com.karuhun.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.karuhun.ftth.inventory.adapter.outbound.persistence.WarehouseTransferStock
import com.karuhun.ftth.inventory.application.port.outbound.*
import com.karuhun.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY)
class ReferenceConsumptionService(private val warehouse: ReferenceWarehouseStore, private val stock: WarehouseTransferStock,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore,
    private val cutovers: InventoryTenantCutoverApi, private val store: ReferenceConsumptionStore) : InventoryReferenceConsumptionApi {
    private val mapper = jacksonObjectMapper()

    override fun <T> consume(command: ReferenceConsumptionCommand,
        seal: (ReferenceConsumptionResult) -> ReferenceConsumptionProof<T>): ReferenceConsumptionProof<T> {
        command.authority.fence.assertHeld()
        val actor = command.authority.fence.identity.userId
        val source = if (command.selections.isEmpty()) null else warehouse.technicianLocation(actor, command.technicianName)
        val lines = command.selections.sortedBy { it.stockIdentityId.toString() }.map { selection ->
            val piece = stock.get(selection.stockIdentityId, requireNotNull(source))
            stock.assertUnallocated(selection.stockIdentityId)
            stock.assertTechnician(piece, actor)
            val amount = positiveReceiptQuantity(selection.quantityBase, piece.unit).quantityBase
            if (amount > piece.quantity) warehouseFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (piece.tracking.name == "SERIAL" && amount != 1L) warehouseFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            TransferLine(UUID.randomUUID(), piece, amount)
        }
        val document = if (lines.isEmpty()) null else UUID.randomUUID()
        val destination = if (lines.isEmpty()) null else store.consumedLocation()
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
            ReferenceConsumedMaterial(line.id, piece.dimension.stockIdentityId, consumed, piece.dimension.skuId, position.skuName,
                line.quantity.toString(), piece.unit.name, piece.tracking.name, position.serial, position.mac)
        }
        val proof = seal(ReferenceConsumptionResult(document, materials))
        if (document != null) {
            store.draft(document, command.workOrderId, command.customerId, requireNotNull(destination), lines, actor,
                command.authority.fence.epoch, command.cutoverEpoch, command.notes)
            val operation = PostingOperation(UUID.randomUUID(), "warehouse.reference.consume", "complete:${command.workOrderId}:${command.revision}",
                actor, document, "reference:$document", command.request.hash, "CONSUME", 201, mapper.writeValueAsString(proof.value),
                command.authority.fence.epoch, command.completedAt)
            val post = WarehousePost(document, 0, "POSTED", operation, MovementKind.CONSUME,
                command.notes.ifBlank { "Material work order" }, legs, splits = splits)
            warehouse.bind(post, requireNotNull(source), destination, "CONSUME")
            posting.post(post, cutovers.lockForCommand(command.cutoverEpoch, WarehouseOperationClass.REFERENCE_STOCK))
            operations.storeIdentity(operation.id, command.request.json, command.authority.fence.identity.sessionId)
        }
        return proof
    }
}
