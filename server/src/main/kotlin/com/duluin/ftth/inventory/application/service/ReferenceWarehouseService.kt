package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.application.port.outbound.TenantOwnerStore
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import com.duluin.ftth.network.SiteReferenceApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional
class ReferenceWarehouseService(private val cutovers: InventoryTenantPolicyService, private val authority: CurrentAuthorityApi,
    private val owners: TenantOwnerStore, private val scopes: InventoryWarehouseScopeApi,
    private val masters: WarehouseMasterStore, private val masterService: WarehouseMasterService,
    private val validation: ReceiptDraftValidation, private val receipts: WarehouseReceiptPersistence,
    private val origins: WarehouseReceiptOrigins, private val stock: WarehouseTransferStock,
    private val store: ReferenceWarehouseStore, private val operations: WarehouseOperationStore,
    private val posting: WarehousePosting, private val sites: SiteReferenceApi, private val iam: IamApi) {
    private val mapper = jacksonObjectMapper()

    fun drain(input: ReferenceDrainInput): TenantCutoverSnapshot {
        val fence = cutovers.lockCurrentForTransition()
        owner(authority.lockCurrent())
        if (input.expectedEpoch != fence.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        return cutovers.beginDraining(input.expectedEpoch)
    }

    fun review(): String {
        cutovers.lockCurrentForTransition()
        owner(authority.lockCurrent())
        return store.review()
    }

    fun activate(input: ReferenceActivationInput, key: String): String {
        receiptKey(key)
        cutovers.lockCurrentForTransition()
        val current = authority.lockCurrent()
        owner(current)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        store.activation(key)?.let { prior ->
            if (prior.first != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.second.first != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            return prior.second.second
        }
        return store.activate(current.fence.identity.userId, current.fence.epoch, key, canonical.json)
    }

    fun receive(input: ReferenceReceiptInput, key: String): WarehouseOperationReceipt = receive(input, key, "warehouse.stock.manage")

    internal fun receiveForRequest(input: ReferenceReceiptInput, key: String): WarehouseOperationReceipt = receive(input, key, "warehouse.request.receive")
    internal fun receiveForCount(input: ReferenceReceiptInput, key: String, countId: UUID): WarehouseOperationReceipt =
        receive(input, key, "warehouse.count.manage", countId)

    private fun receive(input: ReferenceReceiptInput, key: String, permission: String, countId: UUID? = null): WarehouseOperationReceipt {
        val access = access(permission, key)
        notes(input.notes)
        val destination = if (countId == null) warehouse(input.warehouseId, access) else {
            val location = masters.get(MasterKind.LOCATION, input.warehouseId, true) as LocationSnapshot
            if (!store.countLocationVisible(access.current.fence.identity.userId, location.id)) masterFailure(WarehouseErrorCode.NOT_FOUND)
            if (location.state != WarehouseMasterState.ACTIVE || location.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN, LocationKind.TECHNICIAN))
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            location
        }
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        val action = if (countId == null) "RECEIPT" else "COUNT_RECEIPT"
        val namespace = "warehouse.reference.${action.lowercase()}"
        replay(namespace, key, canonical, access)?.let { return it }
        val id = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val source = masters.get(MasterKind.LOCATION, store.receiptBoundary(), true) as LocationSnapshot
        val supplier = masters.get(MasterKind.SUPPLIER, input.supplierId ?: store.receiptSupplier(), true) as SupplierSnapshot
        if (supplier.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val reference = input.reference ?: "Penerimaan $id"
        receiptText(reference, 500)
        val lines = input.lines.mapIndexed { index, line ->
            val sku = masters.get(MasterKind.SKU, line.skuId, true) as SkuSnapshot
            if (sku.tracking == WarehouseTracking.SERIAL || line.lotCode != null) line
            else line.copy(lotCode = "RCV-$id-${index + 1}")
        }
        val intake = ReceiptIntake(supplier, reference, source, destination, validation.prepareLines(lines))
        receipts.saveDraft(id, intake, 0, access.current.fence.identity.userId, access.current.fence.epoch,
            access.cutover.snapshot.epoch, true, condition = WarehouseCondition.SERVICEABLE, technicianCustody = destination.kind == LocationKind.TECHNICIAN)
        val legs = origins.admit(receipts.get(id), operationId, WarehouseCondition.SERVICEABLE, technicianCustody = destination.kind == LocationKind.TECHNICIAN)
        val view = ReferenceMovementView(id, operationId, 1, action, "PUTAWAY", destination.id, null,
            input.notes, referenceTimestamp())
        val command = WarehousePost(id, 0, "PUTAWAY", operation(view, namespace, key, canonical, access), MovementKind.RECEIVE,
            input.notes.ifBlank { "Penerimaan barang" }, legs)
        if (countId != null) store.bindCountReceipt(countId, id)
        return post(command, source.id, destination.id, action, canonical, access)
    }

    fun transfer(input: ReferenceTransferInput, key: String): WarehouseOperationReceipt {
        val access = access("warehouse.stock.manage", key)
        notes(input.notes)
        if (input.sourceWarehouseId == input.warehouseId || input.lines.isEmpty() || input.lines.size > 100 ||
            input.lines.distinctBy { it.stockIdentityId }.size != input.lines.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val source = warehouse(input.sourceWarehouseId, access)
        val destination = warehouse(input.warehouseId, access)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        val namespace = "warehouse.reference.transfer"
        replay(namespace, key, canonical, access)?.let { return it }
        val lines = input.lines.map { selection ->
            val piece = stock.get(selection.stockIdentityId, source.id)
            stock.assertUnallocated(selection.stockIdentityId)
            if (piece.status != InventoryStatus.AVAILABLE || piece.dimension.condition != WarehouseCondition.SERVICEABLE ||
                piece.dimension.legalOwner != AssetLegalOwner.ISP || piece.dimension.custodianKind != OwnerKind.WAREHOUSE ||
                piece.dimension.custodianId != source.id) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val quantity = transferQuantity(selection.quantityBase)
            if (quantity > piece.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (piece.tracking == WarehouseTracking.SERIAL && quantity != 1L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            TransferLine(UUID.randomUUID(), piece, quantity)
        }
        val id = UUID.randomUUID()
        store.transferDraft(id, source.id, destination.id, lines, access.current.fence.identity.userId,
            access.current.fence.epoch, access.cutover.snapshot.epoch, input.notes)
        val legs = mutableListOf<PostingLeg>()
        val splits = mutableListOf<PostingSplit>()
        lines.forEach { line ->
            val piece = line.source
            val rest = piece.quantity - line.quantity
            val split = piece.unit == WarehouseBaseUnit.MM && rest > 0
            val targetId = if (split) UUID.randomUUID() else piece.dimension.stockIdentityId
            val unit = StockUnit.valueOf(piece.unit.name)
            legs += PostingLeg(LegDirection.OUT, piece.dimension, StockQuantity.of(if (split) piece.quantity else line.quantity, unit), line.id, piece.status)
            legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = targetId, locationId = destination.id,
                custodianId = destination.id), StockQuantity.of(line.quantity, unit), line.id, InventoryStatus.AVAILABLE)
            if (split) {
                val remainderId = UUID.randomUUID()
                legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = remainderId), StockQuantity.of(rest, unit), line.id, piece.status)
                splits += PostingSplit(piece.dimension.stockIdentityId, piece.revision, listOf(
                    SegmentChild(targetId, StockQuantity.of(line.quantity, unit), SegmentKind.CUT),
                    SegmentChild(remainderId, StockQuantity.of(rest, unit), SegmentKind.REMNANT)))
            }
        }
        val view = ReferenceMovementView(id, UUID.randomUUID(), 1, "TRANSFER", "RECEIVED", destination.id, source.id,
            input.notes, java.time.Instant.now())
        val command = WarehousePost(id, 0, "RECEIVED", operation(view, namespace, key, canonical, access), MovementKind.TRANSFER,
            input.notes.ifBlank { "Transfer gudang" }, legs, splits = splits)
        return post(command, source.id, destination.id, "TRANSFER", canonical, access)
    }

    internal fun handover(input: ReferenceTechnicianHandoverInput, key: String): WarehouseOperationReceipt {
        val access = access("warehouse.request.handover", key)
        notes(input.notes)
        if (input.lines.size !in 1..100 || input.lines.distinctBy { it.stockIdentityId }.size != input.lines.size)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val source = warehouse(input.sourceWarehouseId, access)
        val technician = iam.findUser(input.technicianId)?.takeIf { it.active && it.technician }
            ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val destination = store.technicianLocation(technician.id, technician.name)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf(
            "sourceWarehouseId" to source.id, "warehouseId" to destination, "technicianId" to technician.id,
            "skuId" to input.skuId, "lines" to input.lines, "notes" to input.notes)))
        val namespace = "warehouse.reference.handover"
        replay(namespace, key, canonical, access)?.let { return it }
        val lines = input.lines.map { selection ->
            val piece = stock.get(selection.stockIdentityId, source.id)
            stock.assertUnallocated(selection.stockIdentityId)
            if (piece.dimension.skuId != input.skuId || piece.status != InventoryStatus.AVAILABLE ||
                piece.dimension.condition != WarehouseCondition.SERVICEABLE || piece.dimension.legalOwner != AssetLegalOwner.ISP ||
                piece.dimension.custodianKind != OwnerKind.WAREHOUSE || piece.dimension.custodianId != source.id)
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val quantity = transferQuantity(selection.quantityBase)
            if (quantity > piece.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (piece.tracking == WarehouseTracking.SERIAL && quantity != 1L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            TransferLine(UUID.randomUUID(), piece, quantity)
        }
        val id = UUID.randomUUID()
        store.transferDraft(id, source.id, destination, lines, access.current.fence.identity.userId,
            access.current.fence.epoch, access.cutover.snapshot.epoch, input.notes)
        val legs = mutableListOf<PostingLeg>()
        val splits = mutableListOf<PostingSplit>()
        lines.forEach { line ->
            val piece = line.source
            val rest = piece.quantity - line.quantity
            val split = piece.unit == WarehouseBaseUnit.MM && rest > 0
            val targetId = if (split) UUID.randomUUID() else piece.dimension.stockIdentityId
            val unit = StockUnit.valueOf(piece.unit.name)
            legs += PostingLeg(LegDirection.OUT, piece.dimension, StockQuantity.of(if (split) piece.quantity else line.quantity, unit), line.id, piece.status)
            legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = targetId, locationId = destination,
                custodianId = technician.id, custodianKind = OwnerKind.TECHNICIAN), StockQuantity.of(line.quantity, unit), line.id, InventoryStatus.ISSUED)
            if (split) {
                val remainderId = UUID.randomUUID()
                legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = remainderId), StockQuantity.of(rest, unit), line.id, piece.status)
                splits += PostingSplit(piece.dimension.stockIdentityId, piece.revision, listOf(
                    SegmentChild(targetId, StockQuantity.of(line.quantity, unit), SegmentKind.CUT),
                    SegmentChild(remainderId, StockQuantity.of(rest, unit), SegmentKind.REMNANT)))
            }
        }
        val view = ReferenceMovementView(id, UUID.randomUUID(), 1, "HANDOVER", "RECEIVED", destination, source.id,
            input.notes, java.time.Instant.now(), technician.id)
        val command = WarehousePost(id, 0, "RECEIVED", operation(view, namespace, key, canonical, access), MovementKind.TRANSFER,
            input.notes.ifBlank { "Serah terima material" }, legs, splits = splits)
        return post(command, source.id, destination, "HANDOVER", canonical, access)
    }

    internal fun receiveReturn(input: ReferenceTechnicianReturnInput, key: String): WarehouseOperationReceipt {
        val access = access("warehouse.return.manage", key)
        notes(input.notes)
        val destination = warehouse(input.warehouseId, access)
        if (input.lines.size !in 1..100 || input.lines.distinctBy { it.stockIdentityId }.size != input.lines.size)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(input))
        val namespace = "warehouse.reference.return"
        replay(namespace, key, canonical, access)?.let { return it }
        val lines = input.lines.map { selection ->
            val piece = stock.get(selection.stockIdentityId, input.sourceWarehouseId)
            stock.assertUnallocated(selection.stockIdentityId)
            stock.assertTechnician(piece, input.technicianId)
            if (piece.dimension.skuId != input.skuId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val quantity = transferQuantity(selection.quantityBase)
            if (quantity > piece.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
            if (piece.tracking == WarehouseTracking.SERIAL && quantity != 1L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            TransferLine(UUID.randomUUID(), piece, quantity)
        }
        val id = UUID.randomUUID()
        store.transferDraft(id, input.sourceWarehouseId, destination.id, lines, access.current.fence.identity.userId,
            access.current.fence.epoch, access.cutover.snapshot.epoch, input.notes)
        val legs = mutableListOf<PostingLeg>()
        val splits = mutableListOf<PostingSplit>()
        lines.forEach { line ->
            val piece = line.source
            val rest = piece.quantity - line.quantity
            val split = piece.unit == WarehouseBaseUnit.MM && rest > 0
            val targetId = if (split) UUID.randomUUID() else piece.dimension.stockIdentityId
            val unit = StockUnit.valueOf(piece.unit.name)
            legs += PostingLeg(LegDirection.OUT, piece.dimension, StockQuantity.of(if (split) piece.quantity else line.quantity, unit), line.id, piece.status)
            legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = targetId, locationId = destination.id,
                custodianId = destination.id, custodianKind = OwnerKind.WAREHOUSE), StockQuantity.of(line.quantity, unit), line.id, InventoryStatus.AVAILABLE)
            if (split) {
                val remainderId = UUID.randomUUID()
                legs += PostingLeg(LegDirection.IN, piece.dimension.copy(stockIdentityId = remainderId), StockQuantity.of(rest, unit), line.id, piece.status)
                splits += PostingSplit(piece.dimension.stockIdentityId, piece.revision, listOf(
                    SegmentChild(targetId, StockQuantity.of(line.quantity, unit), SegmentKind.CUT),
                    SegmentChild(remainderId, StockQuantity.of(rest, unit), SegmentKind.REMNANT)))
            }
        }
        val view = ReferenceMovementView(id, UUID.randomUUID(), 1, "RETURN", "RECEIVED", destination.id, input.sourceWarehouseId,
            input.notes, java.time.Instant.now(), input.technicianId)
        val command = WarehousePost(id, 0, "RECEIVED", operation(view, namespace, key, canonical, access), MovementKind.RETURN,
            input.notes.ifBlank { "Retur material teknisi" }, legs, splits = splits)
        return post(command, input.sourceWarehouseId, destination.id, "RETURN", canonical, access)
    }

    fun stock(skuId: UUID): ReferenceSkuStock {
        val current = authority.lockCurrent()
        receiptPermission(current, "warehouse.stock.view")
        masters.lockTopology()
        val sku = masters.get(MasterKind.SKU, skuId) as SkuSnapshot
        val locations = locations(current)
        val allowed = locations.map { it.id }.toSet()
        val positions = store.positions(skuId).filter { it.locationId in allowed &&
            (it.holderKind !in setOf("TECHNICIAN", "VEHICLE") || it.holderId == current.fence.identity.userId ||
                "warehouse.request.handover" in current.permissions || current.platformAdmin) }
        val byId = locations.associateBy { it.id }
        val warehouses = locations.filter { it.kind == LocationKind.WAREHOUSE }.map { location ->
            val descendants = locations.filter { candidate ->
                var parent: LocationSnapshot? = candidate
                val visited = mutableSetOf<UUID>()
                var matches = false
                while (parent != null && visited.add(parent.id) && visited.size <= 32) {
                    if (parent.id == location.id) { matches = true; break }
                    parent = parent.parentLocationId?.let(byId::get)
                }
                matches
            }.map { it.id }.toSet()
            val quantity = positions.filter { it.locationId in descendants && it.status == "AVAILABLE" && it.holderKind == "WAREHOUSE" }
                .fold(java.math.BigInteger.ZERO) { total, position -> total + position.quantityBase.toBigInteger() }
            ReferenceWarehouseQuantity(location.id, location.name ?: location.code, quantity.toString())
        }
        return ReferenceSkuStock(sku, warehouses, positions)
    }

    fun history(skuId: UUID, page: Int, size: Int): WarehousePage<ReferenceStockHistory> {
        if (page < 0 || size !in 1..100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        receiptPermission(current, "warehouse.stock.view")
        masters.lockTopology()
        masters.get(MasterKind.SKU, skuId)
        val holder = if (current.platformAdmin || "warehouse.request.handover" in current.permissions) null else current.fence.identity.userId
        return store.history(skuId, locations(current).map { it.id }.toSet(), holder, page, size)
    }

    private fun locations(current: CurrentAuthority): List<LocationSnapshot> {
        val locations = mutableListOf<LocationSnapshot>()
        var page = 0
        do {
            val scope = if (current.platformAdmin) AuthorityScope.Unrestricted else scopes.currentUnderFence(current.fence)
            val areas = if (current.platformAdmin) AuthorityScope.Unrestricted else current.areaScope
            val batch = masters.list(MasterKind.LOCATION, MasterFilter(page = page, size = 100, state = WarehouseMasterState.ACTIVE),
                scope, areas, sites.visibleAreas(areas))
            locations += batch.items.filterIsInstance<LocationSnapshot>()
            page++
        } while (page.toLong() * 100 < batch.totalElements)
        return locations
    }

    private data class Access(val current: CurrentAuthority, val scope: AuthorityScope, val cutover: TenantCutoverFence)
    private fun access(permission: String, key: String): Access {
        receiptKey(key)
        val fence = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.REFERENCE_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, permission)
        masters.lockTopology()
        return Access(current, scopes.currentUnderFence(current.fence), fence)
    }
    private fun warehouse(id: UUID, access: Access): LocationSnapshot {
        val location = masters.get(MasterKind.LOCATION, id, true) as LocationSnapshot
        masterService.authorizeLocation(location, access.current, access.scope)
        if (location.state != WarehouseMasterState.ACTIVE || location.kind !in setOf(LocationKind.WAREHOUSE, LocationKind.BIN) ||
            !location.issueEligible) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return location
    }
    private fun replay(namespace: String, key: String, canonical: WarehouseCanonicalPayload, access: Access): WarehouseOperationReceipt? {
        val prior = operations.lockKey(namespace, key) ?: return null
        if (prior.actorId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        return prior.receipt
    }
    private fun operation(view: ReferenceMovementView, namespace: String, key: String, canonical: WarehouseCanonicalPayload, access: Access) =
        PostingOperation(view.operationId, namespace, key, access.current.fence.identity.userId, view.id, "reference:" + view.id,
            canonical.hash, view.kind, 201, mapper.writeValueAsString(view), access.current.fence.epoch, view.recordedAt)
    private fun post(command: WarehousePost, source: UUID, destination: UUID, action: String,
        canonical: WarehouseCanonicalPayload, access: Access): WarehouseOperationReceipt {
        store.bind(command, source, destination, action)
        val result = posting.post(command, access.cutover)
        operations.storeIdentity(command.operation.id, canonical.json, access.current.fence.identity.sessionId)
        return WarehouseOperationReceipt(result.operationId, command.documentId, result.documentRevision,
            command.operation.originalStatus, command.operation.originalBody, result.recordedAt)
    }
    private fun owner(current: CurrentAuthority) {
        if (owners.findUserId() != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
    }
    private fun notes(value: String) {
        if (value.length > 1000 || value.any { it.isISOControl() && it != '\n' }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
