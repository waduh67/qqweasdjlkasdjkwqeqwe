package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class WarehouseCustomerRmaService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val scopes: InventoryWarehouseScopeApi, private val locations: WarehouseReceiptService,
    private val masters: WarehouseMasterStore, private val returns: WarehouseReturnStore,
    private val store: RmaHandoverStore, private val workOrders: InventoryRmaWorkOrderPort,
    private val operations: WarehouseOperationStore, private val posting: WarehousePosting, private val users: IamApi) : InventoryCustomerRmaApi {
    private val mapper = jacksonObjectMapper()

    override fun dispatch(returnId: UUID, request: CustomerRmaDispatch, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        validate(request.expectedRevision, request.observedSerial, request.evidenceReference, metadata)
        if (request.workOrderRevision < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.manage")
        masters.lockTopology()
        val returned = returns.get(returnId, true)
        authorizeLocations(listOf(returned.view.locationId, request.transitLocationId, request.technicianLocationId), current)
        val canonical = payload(returnId, request)
        operations.lockKey("warehouse.rma.dispatch", metadata.idempotencyKey)?.let { prior ->
            val handover = store.get(prior.resourceId)
            authorize(handover, current)
            return replay(prior, canonical, handover.view.id, current, cutover)
        }
        val source = returned.view
        if (source.revision != request.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (source.origin != WarehouseReturnOrigin.ASSET_REMOVAL || source.legalOwner != AssetLegalOwner.CUSTOMER ||
            source.condition != WarehouseCondition.SERVICEABLE || source.inspection?.resetConfirmed != true)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val origin = store.origin(returned)
        val serial = origin.source.serial ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (!returnSerialMatches(serial, request.observedSerial) || origin.source.quantity != 1L || origin.source.tracking != WarehouseTracking.SERIAL)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (request.technicianId == current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        workOrders.lock(request.workOrderId, request.workOrderRevision, origin.customerId, request.technicianId, current.fence)
        val transit = masters.get(MasterKind.LOCATION, request.transitLocationId, true) as LocationSnapshot
        val field = masters.get(MasterKind.LOCATION, request.technicianLocationId, true) as LocationSnapshot
        if (transit.kind != LocationKind.TRANSIT || field.kind != LocationKind.TECHNICIAN || field.custodianId != request.technicianId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val initial = CustomerRmaHandover(UUID.randomUUID(), returnId, origin.repairCaseId, origin.originalAssignmentId, origin.customerId,
            request.workOrderId, request.workOrderRevision, request.technicianId, source.stockIdentityId, source.skuId, serial,
            source.locationId, request.transitLocationId, request.technicianLocationId, AssetLegalOwner.CUSTOMER,
            0, CustomerRmaHandoverState.DRAFT, source.locationId, current.fence.identity.userId, Instant.now())
        val record = RmaHandoverRecord(request, origin, initial)
        store.create(record, cutover.snapshot.epoch, current.fence.epoch, workOrders.read(request.workOrderId, origin.customerId, current.fence, true).code)
        val view = initial.copy(revision = 1, state = CustomerRmaHandoverState.DISPATCHED, locationId = request.transitLocationId)
        return move(record, view, origin.source.dimension,
            origin.source.dimension.copy(locationId = view.transitLocationId, custodianId = view.technicianId, custodianKind = OwnerKind.TRANSIT),
            InventoryStatus.QUARANTINE, InventoryStatus.IN_TRANSIT, "dispatch", request.evidenceReference, canonical, metadata, current, cutover)
    }

    override fun acknowledge(id: UUID, request: CustomerRmaReceipt, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        validate(request.expectedRevision, request.observedSerial, request.evidenceReference, metadata)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "workorder.order.field")
        masters.lockTopology()
        val record = store.get(id, true)
        if (record.view.technicianId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        authorize(record, current)
        val canonical = payload(id, request)
        operations.lockKey("warehouse.rma.acknowledge", metadata.idempotencyKey)?.let {
            return replay(it, canonical, id, current, cutover)
        }
        if (record.view.revision != request.expectedRevision || record.view.state != CustomerRmaHandoverState.DISPATCHED)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (!returnSerialMatches(record.view.serial, request.observedSerial)) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        store.receive(id, current.fence.identity.userId, request)
        val view = record.view.copy(revision = 2, state = CustomerRmaHandoverState.RECEIVED,
            locationId = record.view.technicianLocationId, recordedAt = Instant.now())
        val source = record.origin.source.dimension.copy(locationId = view.transitLocationId,
            custodianId = view.technicianId, custodianKind = OwnerKind.TRANSIT)
        return move(record, view, source, source.copy(locationId = view.technicianLocationId, custodianKind = OwnerKind.TECHNICIAN),
            InventoryStatus.IN_TRANSIT, InventoryStatus.ISSUED, "acknowledge", request.evidenceReference, canonical, metadata, current, cutover)
    }

    override fun get(id: UUID): CustomerRmaHandover {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        masters.lockTopology()
        val record = store.get(id)
        if (current.fence.identity.userId == record.view.technicianId) receiptPermission(current, "workorder.order.field")
        else receiptPermission(current, "inventory.return.manage")
        authorize(record, current, false)
        return record.view
    }

    override fun details(id: UUID): CustomerRmaHandoverDetails {
        val view = get(id)
        val current = authority.lockCurrent()
        val order = workOrders.read(view.workOrderId, view.customerId, current.fence, false)
        val people = users.usersByIds(setOf(view.createdBy, view.technicianId)).associate { it.id to it.name }
        val names = listOf(view.sourceLocationId, view.transitLocationId, view.technicianLocationId).distinct().map {
            val location = masters.get(MasterKind.LOCATION, it) as LocationSnapshot
            WarehouseReturnNamedRef(it, location.code, location.name)
        }
        return CustomerRmaHandoverDetails(view, order.code, order.title, people[view.createdBy], people[view.technicianId], names)
    }

    override fun workOrder(returnId: UUID, workOrderId: UUID): CustomerRmaWorkOrder {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.return.manage")
        masters.lockTopology()
        val returned = returns.get(returnId)
        val source = returned.view
        authorizeLocations(listOfNotNull(returned.intake.quarantineLocationId, source.locationId, source.repair?.repairLocationId), current)
        if (source.origin != WarehouseReturnOrigin.ASSET_REMOVAL || source.legalOwner != AssetLegalOwner.CUSTOMER ||
            source.condition != WarehouseCondition.SERVICEABLE || source.inspection?.resetConfirmed != true)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val origin = store.origin(returned)
        return workOrders.read(workOrderId, origin.customerId, current.fence, true)
    }

    private fun authorize(record: RmaHandoverRecord, current: CurrentAuthority, requireCurrent: Boolean = true) {
        val view = record.view
        authorizeLocations(listOf(view.sourceLocationId, view.transitLocationId, view.technicianLocationId), current)
        workOrders.lock(view.workOrderId, view.workOrderRevision, view.customerId, view.technicianId, current.fence, requireCurrent)
    }
    private fun authorizeLocations(ids: List<UUID>, current: CurrentAuthority) {
        val scope = scopes.currentUnderFence(current.fence)
        ids.distinct().sortedBy(UUID::toString).forEach { locations.authorizeLocation(it, current, scope) }
    }
    private fun replay(prior: StoredWarehouseOperation, canonical: WarehouseCanonicalPayload, id: UUID,
        current: CurrentAuthority, cutover: TenantCutoverFence): WarehouseOperationReceipt {
        if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if (prior.resourceId != id || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        return prior.receipt
    }
    private fun move(record: RmaHandoverRecord, view: CustomerRmaHandover, source: PostingDimension, destination: PostingDimension,
        sourceStatus: InventoryStatus, destinationStatus: InventoryStatus, action: String, reason: String,
        canonical: WarehouseCanonicalPayload, metadata: WarehouseMutationMetadata, current: CurrentAuthority,
        cutover: TenantCutoverFence): WarehouseOperationReceipt {
        val operation = PostingOperation(UUID.randomUUID(), "warehouse.rma.$action", metadata.idempotencyKey,
            current.fence.identity.userId, view.id, "rma:${view.id}", canonical.hash, action.uppercase(), if (view.revision == 1L) 201 else 200,
            mapper.writeValueAsString(view), current.fence.epoch, view.recordedAt)
        val quantity = StockQuantity.of(1, StockUnit.EA)
        posting.post(WarehousePost(view.id, record.view.revision, view.state.name, operation, MovementKind.REPAIR, reason,
            listOf(PostingLeg(LegDirection.OUT, source, quantity, view.id, sourceStatus),
                PostingLeg(LegDirection.IN, destination, quantity, view.id, destinationStatus))), cutover)
        operations.storeIdentity(operation.id, canonical.json, current.fence.identity.sessionId)
        return WarehouseOperationReceipt(operation.id, view.id, view.revision, operation.originalStatus, operation.originalBody, view.recordedAt)
    }
    private fun validate(revision: Long, serial: String, evidence: String, metadata: WarehouseMutationMetadata) {
        receiptKey(metadata.idempotencyKey)
        if (revision !in 0 until Long.MAX_VALUE || listOf(serial, evidence).any { it.isBlank() || it.length > 500 })
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
    private fun payload(id: UUID, request: Any) = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("id" to id, "request" to request)))
}
