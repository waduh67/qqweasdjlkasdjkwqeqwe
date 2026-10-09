package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.identity.MacIdentity
import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional
class ReferenceCountService(private val accessService: ReferenceRequestService, private val store: ReferenceCountStore,
    private val masters: WarehouseMasterStore, private val owners: TenantOwnerStore,
    private val stock: WarehouseTransferStock, private val warehouse: ReferenceWarehouseService, private val bindings: ReferenceWarehouseStore,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore, private val iam: IamApi) {
    private val mapper = jacksonObjectMapper()

    fun load(input: ReferenceCountLoad): ReferenceCountSnapshot {
        val access = accessService.access("count-load:${UUID.randomUUID()}")
        admin(access)
        val location = location(input.locationId, access)
        val sku = masters.get(MasterKind.SKU, input.skuId, true) as SkuSnapshot
        if (sku.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val (state, hash) = store.capture(sku.id, location.id)
        val positions = store.positions(state)
        val total = positions.fold(0L) { sum, position -> checkedStockArithmetic { Math.addExact(sum, position.quantityBase.toLong()) } }
        val view = ReferenceCountSnapshot(UUID.randomUUID(), sku.id, sku.name, location.id, location.name ?: location.code, sku.baseUnit,
            sku.tracking, total.toString(), positions, hash, referenceTimestamp())
        store.load(view, state, access.current.fence.identity.userId, access.current.fence.epoch, access.cutover.snapshot.epoch)
        return view
    }

    fun save(input: ReferenceCountInput, key: String): ReferenceCountView {
        val access = accessService.access(key)
        admin(access)
        receiptText(input.reason, 1000)
        val canonical = accessService.canonical(null, input)
        store.lockKey(key)?.let { (hash, prior) ->
            location(prior.snapshot.locationId, access)
            if (prior.actorId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (store.snapshot(prior.snapshot.id).second != access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return prior
        }
        val (snapshot, epoch) = store.snapshot(input.snapshotId)
        val location = location(snapshot.locationId, access)
        if (epoch != access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        val sku = accessService.sku(snapshot.skuId, snapshot.baseUnit)
        if (sku.tracking != snapshot.tracking || store.capture(sku.id, location.id).second != snapshot.snapshotHash)
            masterFailure(WarehouseErrorCode.STALE_REVISION, "Stok berubah. Muat saldo opname kembali")
        val physical = StockQuantity.parseBase(input.physicalBase, StockUnit.valueOf(sku.baseUnit.name)).quantityBase
        val missing = store.missing(sku.id, location.id)
        val countId = UUID.randomUUID()
        store.begin(countId, snapshot.id, key, access.current.fence.identity.userId, access.current.fence.epoch, access.cutover.snapshot.epoch, canonical)
        val movements = mutableListOf<UUID>()
        val losses = mutableListOf<ReferenceCountSelection>()
        val recoveries = mutableListOf<ReferenceCountSelection>()
        val newSerials = mutableListOf<ReceiptSerialInput>()
        var surplus = 0L
        if (sku.tracking == WarehouseTracking.SERIAL) {
            if (physical != input.serials.size.toLong() || input.serials.size > 500) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val selected = input.serials.associateBy { SerialIdentity.parse(it.serial).canonical }
            if (selected.size != input.serials.size || input.serials.mapNotNull { it.mac?.let { mac -> MacIdentity.parse(mac).canonical } }.let {
                it.size != it.toSet().size }) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            val current = snapshot.positions.associateBy { SerialIdentity.parse(requireNotNull(it.serial)).canonical }
            val lost = missing.associateBy { SerialIdentity.parse(requireNotNull(it.serial)).canonical }
            current.filterKeys { it !in selected }.values.forEach { losses += ReferenceCountSelection(it.balanceId, it.dimension.stockIdentityId, "1") }
            selected.forEach { (serial, value) ->
                receiptText(value.serial, 128)
                val existing = current[serial] ?: lost[serial]
                if (existing != null) {
                    if (value.mac != null && value.mac?.let { MacIdentity.parse(it).canonical } != existing.mac?.let { MacIdentity.parse(it).canonical })
                        masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    if (serial !in current) recoveries += ReferenceCountSelection(existing.balanceId, existing.dimension.stockIdentityId, "1")
                } else {
                    if (store.claimed(value.serial) != null) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Serial berada di lokasi lain atau sudah dipakai")
                    newSerials += value
                }
            }
            surplus = newSerials.size.toLong()
        } else {
            if (input.serials.isNotEmpty()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val difference = physical - snapshot.bookBase.toLong()
            var remaining = if (difference < 0) -difference else difference
            val source = if (difference < 0) snapshot.positions else missing
            source.sortedBy { it.balanceId.toString() }.forEach { position ->
                val amount = minOf(remaining, position.quantityBase.toLong())
                if (amount > 0) {
                    (if (difference < 0) losses else recoveries) += ReferenceCountSelection(position.balanceId, position.dimension.stockIdentityId, amount.toString())
                    remaining -= amount
                }
            }
            if (difference > 0) surplus = remaining
        }
        if (losses.isNotEmpty()) movements += adjust(countId, "LOSS", location, sku, snapshot.positions, losses, input.reason, access)
        if (recoveries.isNotEmpty()) movements += adjust(countId, "RECOVER", location, sku, missing, recoveries, input.reason, access)
        if (surplus > 0) {
            movements += warehouse.receiveForCount(ReferenceReceiptInput(location.id, listOf(ReceiptLineInput(sku.id, surplus.toString(),
                serials = newSerials, lotCode = if (sku.tracking == WarehouseTracking.SERIAL) null else "OPN-$countId")), input.reason),
                "count:$countId:surplus", countId).documentId
        }
        val actor = iam.findUser(access.current.fence.identity.userId) ?: masterFailure(WarehouseErrorCode.FORBIDDEN)
        val view = ReferenceCountView(countId, snapshot, physical.toString(), (physical - snapshot.bookBase.toLong()).toString(),
            input.serials, input.reason, actor.id, actor.name, referenceTimestamp(), movements)
        store.save(view, key, canonical, access.current.fence.epoch, access.cutover.snapshot.epoch)
        return view
    }

    fun detail(id: UUID): ReferenceCountView {
        val access = accessService.readAccess()
        admin(access)
        return store.get(id).also { location(it.snapshot.locationId, access) }
    }

    fun list(page: Int, size: Int): WarehousePage<ReferenceCountView> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val access = accessService.readAccess()
        admin(access)
        return store.list(page, size, store.visibleLocations(access.current.fence.identity.userId))
    }

    fun locations(search: String?, page: Int, size: Int): WarehousePage<ReferenceCountLocation> {
        if (page < 0 || size !in 1..100 || search != null && search.length > 200) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val access = accessService.readAccess()
        admin(access)
        return store.locations(access.current.fence.identity.userId, search, page, size)
    }

    private fun admin(access: ReferenceRequestService.Access) {
        val actor = access.current.fence.identity.userId
        if (owners.findUserId() != actor) {
            if (!store.isAdmin(actor)) masterFailure(WarehouseErrorCode.FORBIDDEN)
            accessService.permission(access.current, "warehouse.count.manage")
        }
    }
    private fun location(id: UUID, access: ReferenceRequestService.Access): LocationSnapshot {
        val location = masters.get(MasterKind.LOCATION, id, true) as LocationSnapshot
        if (id !in store.visibleLocations(access.current.fence.identity.userId)) masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (location.state != WarehouseMasterState.ACTIVE || location.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN, LocationKind.TECHNICIAN))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (location.kind == LocationKind.TECHNICIAN && iam.findUser(requireNotNull(location.custodianId))?.let { it.active && it.technician } != true)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return location
    }

    private fun adjust(count: UUID, direction: String, location: LocationSnapshot, sku: SkuSnapshot, positions: List<ReferenceCountPosition>,
        selections: List<ReferenceCountSelection>, reason: String, access: ReferenceRequestService.Access): UUID {
        val loss = direction == "LOSS"
        val missing = store.missingLocation(location)
        val source = if (loss) location.id else missing
        val destination = if (loss) missing else location.id
        val lines = selections.map { selected ->
            val position = positions.single { it.balanceId == selected.balanceId && it.dimension.stockIdentityId == selected.stockIdentityId }
            val piece = stock.get(selected.stockIdentityId, source, position.balanceId, position.dimension)
            stock.assertUnallocated(selected.stockIdentityId)
            TransferLine(UUID.randomUUID(), piece, selected.quantityBase.toLong())
        }
        val id = UUID.randomUUID()
        store.draft(id, source, destination, lines, access.current.fence.identity.userId, access.current.fence.epoch, access.cutover.snapshot.epoch, reason)
        val legs = mutableListOf<PostingLeg>()
        val splits = mutableListOf<PostingSplit>()
        lines.forEach { line ->
            val piece = line.source
            val rest = piece.quantity - line.quantity
            val split = piece.unit == WarehouseBaseUnit.MM && rest > 0
            val target = if (split) UUID.randomUUID() else piece.dimension.stockIdentityId
            val unit = StockUnit.valueOf(piece.unit.name)
            val targetDimension = if (loss) piece.dimension.copy(stockIdentityId = target, locationId = missing, custodianId = missing,
                custodianKind = OwnerKind.WAREHOUSE, condition = WarehouseCondition.QUARANTINE)
                else piece.dimension.copy(stockIdentityId = target, locationId = location.id, custodianId = location.custodianId ?: location.id,
                    custodianKind = if (location.kind == LocationKind.TECHNICIAN) OwnerKind.TECHNICIAN else OwnerKind.WAREHOUSE, condition = WarehouseCondition.SERVICEABLE)
            legs += PostingLeg(LegDirection.OUT, piece.dimension, StockQuantity.of(if (split) piece.quantity else line.quantity, unit), line.id, piece.status)
            legs += PostingLeg(LegDirection.IN, targetDimension, StockQuantity.of(line.quantity, unit), line.id,
                if (loss) InventoryStatus.LOST else if (location.kind == LocationKind.TECHNICIAN) InventoryStatus.ISSUED else InventoryStatus.AVAILABLE)
            if (split) {
                val retained = UUID.randomUUID()
                legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = retained), StockQuantity.of(rest, unit), line.id, piece.status)
                splits += PostingSplit(piece.dimension.stockIdentityId, piece.revision, listOf(SegmentChild(target, StockQuantity.of(line.quantity, unit), SegmentKind.CUT),
                    SegmentChild(retained, StockQuantity.of(rest, unit), SegmentKind.REMNANT)))
            }
        }
        val action = "COUNT_$direction"
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(ReferenceCountMovementInput(count, direction, location.id, sku.id, selections, reason)))
        val operationId = UUID.randomUUID()
        val view = ReferenceMovementView(id, operationId, 1, action, "POSTED", destination, source, reason, referenceTimestamp())
        val operation = PostingOperation(operationId, "warehouse.reference.${action.lowercase()}", "count:$count:${direction.lowercase()}",
            access.current.fence.identity.userId, id, "reference:$id", canonical.hash, action, 201, mapper.writeValueAsString(view), access.current.fence.epoch, view.recordedAt)
        val command = WarehousePost(id, 0, "POSTED", operation, MovementKind.COUNT_VARIANCE, reason, legs, splits = splits)
        bindings.bind(command, source, destination, action)
        store.bind(count, id, direction)
        posting.post(command, access.cutover)
        operations.storeIdentity(operationId, canonical.json, access.current.fence.identity.sessionId)
        return id
    }
}
