package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class], timeout = 30)
class WarehouseRepairService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val locations: WarehouseReceiptService,
    private val masters: WarehouseMasterStore, private val returns: WarehouseReturnStore,
    private val repairs: WarehouseRepairStore, private val operations: WarehouseOperationStore,
    private val posting: WarehousePosting) : InventoryReturnRepairApi {
    private val mapper = jacksonObjectMapper()

    override fun dispatch(id: UUID, request: WarehouseRepairDispatch, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        validate(request.expectedRevision, request.observedSerial, request.vendorReference, request.evidenceReference, metadata)
        val access = access(id, request.repairLocationId)
        val canonical = payload(id, request)
        replay("repair-dispatch", metadata, canonical, access)?.let { return it }
        val source = access.record.view
        if (source.revision != request.expectedRevision || source.state != WarehouseReturnState.RECEIVED_IN_INSPECTION || source.repair != null)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (source.origin != WarehouseReturnOrigin.ASSET_REMOVAL || source.inspection == null ||
            access.destination.kind != LocationKind.TRANSIT || access.destination.issueEligible)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val supplier = masters.get(MasterKind.SUPPLIER, request.vendorId, true) as SupplierSnapshot
        if (supplier.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val physical = returns.position(access.record.source.dimension.copy(locationId = source.locationId,
            custodianId = source.locationId, custodianKind = OwnerKind.WAREHOUSE, condition = source.condition))
        serial(physical, request.observedSerial)
        val progress = WarehouseRepairProgress(UUID.randomUUID(), request.vendorId, request.vendorReference, request.repairLocationId, source.revision + 1)
        val view = source.copy(revision = source.revision + 1, state = WarehouseReturnState.REPAIR,
            locationId = request.repairLocationId, repair = progress, recordedAt = Instant.now())
        repairs.dispatch(source, progress, request)
        return move(access, physical, view, physical.dimension.copy(locationId = view.locationId,
            custodianId = request.vendorId, custodianKind = OwnerKind.REPAIR), "repair-dispatch", request.evidenceReference, canonical, metadata)
    }

    override fun receive(id: UUID, request: WarehouseRepairReceipt, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        validate(request.expectedRevision, request.observedSerial, request.vendorReference, request.evidenceReference, metadata)
        val access = access(id, request.quarantineLocationId)
        val canonical = payload(id, request)
        replay("repair-receive", metadata, canonical, access)?.let { return it }
        val source = access.record.view
        val repair = source.repair ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (source.revision != request.expectedRevision || source.state != WarehouseReturnState.REPAIR || repair.returnedRevision != null)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (access.destination.kind != LocationKind.QUARANTINE || access.destination.issueEligible)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val physical = returns.position(access.record.source.dimension.copy(locationId = source.locationId,
            custodianId = repair.vendorId, custodianKind = OwnerKind.REPAIR, condition = source.condition))
        serial(physical, request.observedSerial)
        val view = source.copy(revision = source.revision + 1, state = WarehouseReturnState.RECEIVED_IN_INSPECTION,
            locationId = request.quarantineLocationId, recordedAt = Instant.now(), repair = repair.copy(
                returnedRevision = source.revision + 1, result = request.result, receiptReference = request.vendorReference))
        repairs.receive(repair.id, request)
        return move(access, physical, view, physical.dimension.copy(locationId = view.locationId,
            custodianId = view.locationId, custodianKind = OwnerKind.WAREHOUSE), "repair-receive", request.evidenceReference, canonical, metadata)
    }

    private data class Access(val record: WarehouseReturnRecord, val current: CurrentAuthority,
        val cutover: TenantCutoverFence, val destination: LocationSnapshot)

    private fun access(id: UUID, destinationId: UUID): Access {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.manage")
        masters.lockTopology()
        val record = returns.get(id, true)
        val scope = scopes.currentUnderFence(current.fence)
        val authorized = (listOf(record.intake.quarantineLocationId, record.view.locationId, destinationId) +
            listOfNotNull(record.view.repair?.repairLocationId)).distinct().sortedBy(UUID::toString).associateWith {
                locations.authorizeLocation(it, current, scope)
            }
        return Access(record, current, cutover, authorized.getValue(destinationId))
    }

    private fun replay(action: String, metadata: WarehouseMutationMetadata, canonical: WarehouseCanonicalPayload, access: Access): WarehouseOperationReceipt? {
        val prior = operations.lockKey("warehouse.return.$action", metadata.idempotencyKey) ?: return null
        if (prior.actorId != access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.hash != canonical.hash || prior.resourceId != access.record.view.id) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        return prior.receipt
    }

    private fun move(access: Access, source: WarehouseReturnSource, view: WarehouseReturnView, destination: PostingDimension,
        action: String, reason: String, canonical: WarehouseCanonicalPayload, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val operation = PostingOperation(UUID.randomUUID(), "warehouse.return.$action", metadata.idempotencyKey,
            access.current.fence.identity.userId, view.id, "return:${view.id}", canonical.hash, action.uppercase(), 200,
            mapper.writeValueAsString(view), access.current.fence.epoch, view.recordedAt)
        val quantity = StockQuantity.of(1, StockUnit.EA)
        posting.post(WarehousePost(view.id, access.record.view.revision, view.state.name, operation, MovementKind.REPAIR, reason,
            listOf(PostingLeg(LegDirection.OUT, source.dimension, quantity, view.id, InventoryStatus.QUARANTINE),
                PostingLeg(LegDirection.IN, destination, quantity, view.id, InventoryStatus.QUARANTINE))), access.cutover)
        operations.storeIdentity(operation.id, canonical.json, access.current.fence.identity.sessionId)
        return WarehouseOperationReceipt(operation.id, view.id, view.revision, 200, operation.originalBody, view.recordedAt)
    }

    private fun validate(revision: Long, serial: String, reference: String, evidence: String, metadata: WarehouseMutationMetadata) {
        receiptKey(metadata.idempotencyKey)
        if (revision !in 0 until Long.MAX_VALUE || listOf(serial, reference, evidence).any { it.isBlank() || it.length > 500 })
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
    private fun serial(source: WarehouseReturnSource, observed: String) {
        if (source.tracking != WarehouseTracking.SERIAL || source.quantity != 1L || source.serial != observed)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    private fun payload(id: UUID, request: Any) = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "request" to request)))
}
