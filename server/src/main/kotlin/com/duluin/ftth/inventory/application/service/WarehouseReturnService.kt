package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseReturnService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val locations: WarehouseReceiptService,
    private val masters: WarehouseMasterStore, private val origins: WarehouseReturnOrigins,
    private val store: WarehouseReturnStore, private val repairs: WarehouseRepairStore, private val operations: WarehouseOperationStore,
    private val posting: WarehousePosting) : InventoryReturnApi {
    private val mapper = jacksonObjectMapper()

    override fun receive(request: WarehouseReturnIntake, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        receiptKey(metadata.idempotencyKey)
        evidence(request.evidenceReference)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.manage")
        masters.lockTopology()
        val destination = locations.authorizeLocation(request.quarantineLocationId, current, scopes.currentUnderFence(current.fence))
        if (destination.kind != LocationKind.QUARANTINE || destination.issueEligible) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        val prior = operations.lockKey("warehouse.return.receive", metadata.idempotencyKey)
        if (prior != null) {
            replay(prior, current, cutover, canonical, prior.resourceId)
            authorize(store.get(prior.resourceId), current)
            return prior.receipt
        }
        val source = origins.resolve(request, current)
        val recovered = request.origin == WarehouseReturnOrigin.ASSET_REMOVAL
        val view = WarehouseReturnView(UUID.randomUUID(), 0,
            if (recovered) WarehouseReturnState.DRAFT else WarehouseReturnState.RECEIVED_IN_INSPECTION, request.origin,
            request.sourceDocumentId, source.dimension.stockIdentityId, source.dimension.skuId, source.dimension.lotId,
            source.unit, source.quantity.toString(), source.dimension.locationId, source.dimension.condition,
            source.dimension.legalOwner, current.fence.identity.userId, Instant.now())
        val record = WarehouseReturnRecord(request, source, store.sourceLine(request.sourceDocumentId), view)
        val operation = operation(if (recovered) "open" else "receive", view, metadata, canonical, current, 201)
        store.create(record, operation, cutover.snapshot.epoch)
        operations.storeIdentity(operation.id, canonical.json, current.fence.identity.sessionId)
        return if (recovered) receiveRecovered(record, metadata, canonical, current, cutover) else receipt(view, operation)
    }

    private fun receiveRecovered(record: WarehouseReturnRecord, metadata: WarehouseMutationMetadata,
        canonical: WarehouseCanonicalPayload, current: CurrentAuthority, cutover: TenantCutoverFence): WarehouseOperationReceipt {
        val view = record.view.copy(revision = 1, state = WarehouseReturnState.RECEIVED_IN_INSPECTION,
            locationId = record.intake.quarantineLocationId, recordedAt = Instant.now())
        val operation = operation("receive", view, metadata, canonical, current, 201)
        val quantity = StockQuantity.of(1, StockUnit.EA)
        val destination = record.source.dimension.copy(locationId = view.locationId,
            custodianId = view.locationId, custodianKind = OwnerKind.WAREHOUSE)
        posting.post(WarehousePost(view.id, 0, "RECEIVED_IN_INSPECTION", operation, MovementKind.RETURN,
            record.intake.evidenceReference, listOf(
                PostingLeg(LegDirection.OUT, record.source.dimension, quantity, view.id, InventoryStatus.QUARANTINE),
                PostingLeg(LegDirection.IN, destination, quantity, view.id, InventoryStatus.QUARANTINE))), cutover)
        operations.storeIdentity(operation.id, canonical.json, current.fence.identity.sessionId)
        return receipt(view, operation)
    }

    override fun inspect(id: UUID, request: WarehouseReturnInspection, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        receiptKey(metadata.idempotencyKey)
        evidence(request.evidenceReference)
        if (!request.measuredQuantityBase.matches(Regex("[1-9][0-9]{0,18}")) || request.measuredQuantityBase.toLongOrNull() == null ||
            request.expectedRevision !in 0 until Long.MAX_VALUE) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.manage")
        masters.lockTopology()
        val record = store.get(id, true)
        authorize(record, current)
        val destination = locations.authorizeLocation(request.destinationLocationId, current, scopes.currentUnderFence(current.fence))
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "request" to request)))
        val prior = operations.lockKey("warehouse.return.inspect", metadata.idempotencyKey)
        if (prior != null) {
            replay(prior, current, cutover, canonical, id)
            return prior.receipt
        }
        if (record.view.revision != request.expectedRevision || record.view.state != WarehouseReturnState.RECEIVED_IN_INSPECTION)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val expected = record.source.dimension.copy(locationId = record.view.locationId, custodianId = record.view.locationId,
            custodianKind = OwnerKind.WAREHOUSE, condition = record.view.condition)
        val source = store.position(expected)
        if (source.quantity.toString() != request.measuredQuantityBase || request.measuredQuantityBase != record.view.quantityBase)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (source.tracking == WarehouseTracking.SERIAL && (request.observedSerial != source.serial ||
            request.condition == WarehouseCondition.SERVICEABLE && (!request.resetConfirmed || request.resetEvidenceReference.isNullOrBlank())))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        request.resetEvidenceReference?.let(::evidence)
        if (source.tracking != WarehouseTracking.SERIAL && (request.observedSerial != null || request.resetEvidenceReference != null || request.resetConfirmed))
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        if (request.condition == WarehouseCondition.SCRAP ||
            request.condition == WarehouseCondition.SERVICEABLE && source.dimension.legalOwner == AssetLegalOwner.UNKNOWN)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val released = request.condition == WarehouseCondition.SERVICEABLE && source.dimension.legalOwner == AssetLegalOwner.ISP
        if (released && (destination.kind != LocationKind.BIN || !destination.issueEligible) ||
            !released && (destination.kind != LocationKind.QUARANTINE || destination.issueEligible))
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val view = record.view.copy(revision = record.view.revision + 1,
            state = if (released) WarehouseReturnState.ACCEPTED else WarehouseReturnState.RECEIVED_IN_INSPECTION,
            locationId = destination.id, condition = request.condition, inspection = request, recordedAt = Instant.now())
        val operation = operation("inspect", view, metadata, canonical, current, 200)
        val quantity = StockQuantity.of(source.quantity, StockUnit.valueOf(source.unit.name))
        val target = source.dimension.copy(locationId = destination.id, custodianId = destination.id, condition = request.condition)
        posting.post(WarehousePost(id, record.view.revision, if (released) "ACCEPTED" else "RECEIVED_IN_INSPECTION", operation,
            MovementKind.RETURN, request.evidenceReference, listOf(
                PostingLeg(LegDirection.OUT, source.dimension, quantity, id, InventoryStatus.QUARANTINE),
                PostingLeg(LegDirection.IN, target, quantity, id, if (released) InventoryStatus.AVAILABLE else InventoryStatus.QUARANTINE)),
            events = listOf(PostingEvent(UUID.randomUUID(), WarehouseEventKind.RETURN_RECEIVED, operation.originalBody))), cutover)
        operations.storeIdentity(operation.id, canonical.json, current.fence.identity.sessionId)
        repairs.inspected(view)
        return receipt(view, operation)
    }

    override fun get(id: UUID): WarehouseReturnView {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.view")
        val record = store.get(id)
        authorize(record, current)
        return record.view
    }

    override fun history(id: UUID): List<WarehouseReturnView> {
        get(id)
        return store.history(id)
    }

    private fun authorize(record: WarehouseReturnRecord, current: CurrentAuthority) {
        val scope = scopes.currentUnderFence(current.fence)
        listOf(record.intake.quarantineLocationId, record.view.locationId).distinct().sortedBy(UUID::toString).forEach {
            locations.authorizeLocation(it, current, scope)
        }
    }

    private fun replay(prior: StoredWarehouseOperation, current: CurrentAuthority, cutover: TenantCutoverFence,
        canonical: WarehouseCanonicalPayload, id: UUID) {
        if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != canonical.hash || prior.resourceId != id) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
    }

    private fun evidence(value: String) {
        if (value.isBlank() || value.length > 500) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }

    private fun operation(action: String, view: WarehouseReturnView, metadata: WarehouseMutationMetadata,
        canonical: WarehouseCanonicalPayload, current: CurrentAuthority, status: Int) = PostingOperation(UUID.randomUUID(),
        "warehouse.return.$action", metadata.idempotencyKey, current.fence.identity.userId, view.id, "return:${view.id}",
        canonical.hash, action.uppercase(), status, mapper.writeValueAsString(view), current.fence.epoch, view.recordedAt)

    private fun receipt(view: WarehouseReturnView, operation: PostingOperation) = WarehouseOperationReceipt(
        operation.id, view.id, view.revision, operation.originalStatus, operation.originalBody, operation.recordedAt)
}
