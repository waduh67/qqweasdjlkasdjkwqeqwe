package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class AssetHandoverService(private val cutovers: InventoryTenantCutoverApi, private val authorities: CurrentAuthorityApi,
    private val deployments: DeploymentStore, private val store: AssetHandoverStore,
    private val workOrders: AssetHandoverWorkOrderPort, private val customers: AssetHandoverCustomerPort,
    private val documents: AssetTitlePostingStore, private val posting: WarehousePosting,
    private val operations: WarehouseOperationStore, private val clock: MaterialPlanningStore,
    private val masters: WarehouseMasterStore, private val scopes: InventoryWarehouseScopeApi,
    private val locations: WarehouseReceiptService) {
    private val mapper = jacksonObjectMapper()

    fun accept(request: AcceptAssetHandoverRequest, metadata: WarehouseMutationMetadata): AssetAssignmentRef {
        receiptKey(metadata.idempotencyKey)
        if (request.expectedRevision < 0 || request.expectedTitleRevision < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        val authorization = store.authorization(request.assignmentId)
        val preview = deployments.custodyView(authorization)
        val workOrder = workOrders.lock(preview.binding, current.fence)
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        preview.scopeLocations.distinct().sortedBy(UUID::toString).forEach { locations.authorizeLocation(it, current, scope) }
        store.lock(request.assignmentId, metadata.idempotencyKey)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        store.replay(metadata.idempotencyKey)?.let { prior ->
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.payloadHash != canonical.hash || prior.assignment.assignmentId != request.assignmentId)
                masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            store.assertValid(prior)
            return prior.assignment
        }
        if (request.expectedTitleRevision != 0L) masterFailure(WarehouseErrorCode.STALE_REVISION)
        store.assertPending(request.assignmentId, request.expectedRevision)
        val original = deployments.result(authorization).consumption.assignment
        val rma = original.purpose == DeploymentPurpose.RETURN_CUSTOMER_RMA
        if (!preview.consumed || original.provenance == AssetProvenance.UNKNOWN) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val position = store.position(original)
        val customer = customers.lock(original.customerId, original.assignmentId)
        val signature = workOrders.signature(original.workOrderId, request.evidenceId)
        val acceptedAt = clock.now()
        val handoverId = UUID.randomUUID()
        val assignment = original.copy(revision = Math.addExact(original.revision, 1), handoverState = AssetHandoverState.ACCEPTED,
            acceptedHandover = AcceptedAssetHandover(handoverId, original.assignmentId, original.assetId, original.workOrderId,
                original.customerId, signature.id, acceptedAt), legalOwner = when (original.ownershipMode) {
                    AssetOwnershipMode.LOAN -> AssetLegalOwner.ISP
                    AssetOwnershipMode.SALE -> AssetLegalOwner.CUSTOMER
                }, titleRevision = if (original.ownershipMode == AssetOwnershipMode.SALE && !rma) 1 else 0)
        val record = AssetHandoverRecord(handoverId, "HANDOVER-$handoverId", authorization, assignment,
            current.fence.identity.userId, UUID.randomUUID(), metadata.idempotencyKey, canonical.hash, request.expectedRevision,
            request.expectedTitleRevision, position, workOrder, customer, signature, acceptedAt, current.fence.epoch, cutover.snapshot.epoch)
        documents.create(record)
        store.append(record)
        if (rma) documents.acknowledge(record)
        else when (assignment.ownershipMode) {
            AssetOwnershipMode.LOAN -> documents.acknowledge(record)
            AssetOwnershipMode.SALE -> {
                val operation = PostingOperation(record.operationId, "warehouse.asset.handover", metadata.idempotencyKey, record.actorId,
                    assignment.assignmentId, "workorder:${assignment.workOrderId}", canonical.hash, "ACCEPT", 200,
                    mapper.writeValueAsString(assignment), current.fence.epoch, acceptedAt)
                val quantity = StockQuantity.of(1, StockUnit.EA)
                posting.post(WarehousePost(record.operationId, 0, "POSTED", operation, MovementKind.TITLE_TRANSFER,
                    "Accepted customer sale handover", listOf(
                        PostingLeg(LegDirection.OUT, position.dimension, quantity, record.operationId, InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED),
                        PostingLeg(LegDirection.IN, position.dimension.copy(legalOwner = AssetLegalOwner.CUSTOMER), quantity,
                            record.operationId, InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED))), cutover)
            }
        }
        operations.storeIdentity(record.operationId, canonical.json, current.fence.identity.sessionId)
        store.advance(record)
        return assignment
    }
}
