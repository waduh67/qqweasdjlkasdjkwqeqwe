package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReceiptPersistence
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class WarehouseReceiptService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val masters: WarehouseMasterStore,
    private val masterService: WarehouseMasterService, private val validation: ReceiptDraftValidation,
    private val store: WarehouseReceiptPersistence, private val operations: WarehouseOperationStore) {
    private val mapper = jacksonObjectMapper()

    fun draft(id: UUID?, input: ReceiptDraftInput, key: String): WarehouseOperationReceipt {
        receiptKey(key)
        if ((id == null) != (input.expectedRevision == null) || (input.expectedRevision ?: 0) !in 0 until Long.MAX_VALUE)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.receipt.manage")
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        val action = if (id == null) "CREATE" else "UPDATE"
        val namespace = "warehouse.receipt.${action.lowercase()}"
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "input" to input)))
        val preview = operations.findKey(namespace, key)
        val target = id ?: preview?.resourceId ?: UUID.randomUUID()
        val existing = if (id != null || preview != null) store.get(target, true) else null
        existing?.let { authorize(it.intake, current, scope) }
        authorizeLocation(input.sourceLocationId, current, scope)
        authorizeLocation(input.inspectionLocationId, current, scope)
        val prior = operations.lockKey(namespace, key)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.hash != canonical.hash || prior.resourceId != target) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            authorize(mapper.readValue(operations.identity(prior.receipt.operationId), ReceiptIntake::class.java), current, scope)
            return prior.receipt
        }
        if (existing != null && existing.revision != input.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (existing != null && existing.state != WarehouseReceiptState.DRAFT) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val intake = validation.prepare(input)
        val revision = if (existing == null) 0 else Math.addExact(existing.revision, 1)
        store.saveDraft(target, intake, revision, current.fence.identity.userId, current.fence.epoch, cutover.snapshot.epoch, existing == null)
        val body = mapper.writeValueAsString(view(store.get(target), false))
        val operation = PostingOperation(UUID.randomUUID(), namespace, key, current.fence.identity.userId, target,
            "receipt:$target", canonical.hash, action, if (existing == null) 201 else 200, body, current.fence.epoch)
        store.operation(operation, revision, cutover.snapshot.epoch)
        operations.storeIdentity(operation.id, mapper.writeValueAsString(intake), current.fence.identity.sessionId)
        return WarehouseOperationReceipt(operation.id, target, revision, operation.originalStatus, body, operation.recordedAt)
    }

    @Transactional
    fun get(id: UUID): ReceiptView {
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.receipt.view")
        val record = store.get(id)
        authorize(record.intake, current, scopes.currentUnderFence(current.fence))
        return view(record, current.platformAdmin || "inventory.cost.view" in current.permissions)
    }

    @Transactional
    fun history(id: UUID): List<ReceiptHistory> {
        get(id)
        return store.history(id)
    }

    @Transactional
    fun list(filter: ReceiptFilter): WarehousePage<ReceiptView> {
        if (filter.page < 0 || filter.size !in 1..100 || filter.sort !in setOf("createdAt", "externalReference") || filter.direction !in setOf("asc", "desc") ||
            (filter.from != null && filter.until != null && filter.from >= filter.until) || (filter.serial?.length ?: 0) > 128)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.receipt.view")
        val scope = scopes.currentUnderFence(current.fence)
        val serial = filter.serial?.let { SerialIdentity.parse(it).canonical }
        val records = store.candidates(filter).mapNotNull { id ->
            val record = store.get(id)
            try { authorize(record.intake, current, scope) } catch (failure: WarehouseContractException) {
                if (failure.error.code == WarehouseErrorCode.NOT_FOUND) return@mapNotNull null
                throw failure
            }
            if (serial != null && record.intake.lines.none { it.serial?.let { raw -> SerialIdentity.parse(raw).canonical } == serial }) null else record
        }
        val offset = filter.page.toLong() * filter.size
        val page = if (offset >= records.size) emptyList() else records.drop(offset.toInt()).take(filter.size)
        return WarehousePage(page.map { view(it, current.platformAdmin || "inventory.cost.view" in current.permissions) }, filter.page, filter.size, records.size.toLong())
    }

    internal fun authorize(intake: ReceiptIntake, current: CurrentAuthority, scope: AuthorityScope) {
        for (location in listOf(intake.source, intake.inspection)) {
            masterService.authorizeLocation(location, current, scope)
            val live = authorizeLocation(location.id, current, scope)
            if (location.id == intake.source.id && (live.code != "RECEIPT_SOURCE" || live.kind != com.duluin.ftth.inventory.domain.model.LocationKind.TRANSIT))
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            if (location.id == intake.inspection.id && (live.kind != com.duluin.ftth.inventory.domain.model.LocationKind.QUARANTINE || live.issueEligible))
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        if (masters.get(MasterKind.SUPPLIER, intake.supplier.id).state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        intake.lines.map { it.sku.id }.distinct().forEach {
            if (masters.get(MasterKind.SKU, it).state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        intake.lines.flatMap { store.pieces(it.id) }.map { it.locationId }.distinct().forEach { authorizeLocation(it, current, scope) }
    }

    internal fun authorizeLocation(id: UUID, current: CurrentAuthority, scope: AuthorityScope): LocationSnapshot {
        val location = masters.get(MasterKind.LOCATION, id) as LocationSnapshot
        masterService.authorizeLocation(location, current, scope)
        if (location.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return location
    }

    internal fun view(record: ReceiptRecord, cost: Boolean): ReceiptView {
        val inspections = store.inspections(record.id)
        return ReceiptView(record.id, record.revision, record.state, record.createdAt,
        record.intake.supplier.id, record.intake.supplier.name, record.intake.externalReference, record.intake.source.id, record.intake.inspection.id,
        record.intake.lines.map { line -> ReceiptLineView(line.id, line.inputLineNumber, line.sku.id, line.sku.code, line.sku.name,
            line.sku.tracking, line.sku.baseUnit, line.quantityBase, line.serial, line.mac, line.lotCode, line.sku.inspectionRequired,
            line.conversion, if (cost) line.cost else null, store.pieces(line.id),
            inspections.filter { it.lineId == line.id }.sumOf { it.acceptedBase.toLong() }.toString(),
            inspections.filter { it.lineId == line.id }.sumOf { it.rejectedBase.toLong() }.toString(), store.putawayBase(line.id)) }, inspections)
    }
}

internal fun receiptKey(key: String) {
    if (key.length !in 1..240 || key.any { it.code !in 33..126 }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
internal fun receiptPermission(current: CurrentAuthority, permission: String) {
    if (!current.platformAdmin && permission !in current.permissions) masterFailure(WarehouseErrorCode.FORBIDDEN)
}
