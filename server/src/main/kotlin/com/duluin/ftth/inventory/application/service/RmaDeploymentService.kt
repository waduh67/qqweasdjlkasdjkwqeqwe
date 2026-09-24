package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
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
class RmaDeploymentService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val workOrders: InventoryWorkOrderValidationPort, private val store: RmaDeploymentStore,
    private val deployments: DeploymentStore, private val handovers: RmaHandoverStore,
    private val masters: WarehouseMasterStore, private val locations: WarehouseReceiptService, private val scopes: InventoryWarehouseScopeApi,
    private val assignments: AssetAssignmentStore, private val documents: DeploymentPostingStore, private val posting: WarehousePosting,
    private val operations: WarehouseOperationStore, private val clock: MaterialPlanningStore) {
    private val mapper = jacksonObjectMapper()

    fun authorize(workOrderId: UUID, request: DeploymentIntentRequest, metadata: WarehouseMutationMetadata): DeploymentAuthorizationRef {
        key(metadata)
        if (request.expectedRevision < 0 || request.purpose != DeploymentPurpose.RETURN_CUSTOMER_RMA || request.issueLineId != null ||
            request.previousAssignmentId == null || request.repairCaseId == null || request.ownershipMode != AssetOwnershipMode.SALE)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("workOrderId" to workOrderId, "request" to request)))
        val prior = store.findMint(metadata.idempotencyKey)
        val source = prior?.permit?.source ?: store.source(request.repairCaseId)
        if (source.workOrderId != workOrderId || source.custody.stockIdentityId != request.assetId ||
            source.originalAssignmentId != request.previousAssignmentId || source.repairCaseId != request.repairCaseId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val binding = prior?.permit?.binding ?: DeploymentBinding(UUID.randomUUID(), UUID.randomUUID(), current.fence.identity.tenantId,
            current.fence.identity.userId, request.assetId, request.assetId, null, workOrderId, source.customerId,
            DeploymentPurpose.RETURN_CUSTOMER_RMA, AssetOwnershipMode.SALE,
            MaterialRevisions(request.expectedRevision, 0, deployments.useRevision(workOrderId), 0), null, source.assetRevision,
            current.fence.epoch, cutover.snapshot.epoch, CustomerRmaReturnBinding(source.originalAssignmentId, source.customerId,
                source.repairCaseId, source.repairRevision, source.handoverId), source.originalAssignmentId, source.originalAssignmentRevision)
        workOrders.lockAndValidate(DeploymentValidationContext(binding, current.fence, cutover))
        deployments.lockKey(metadata.idempotencyKey)
        store.findMint(metadata.idempotencyKey)?.let { replay ->
            if (replay.permit.binding.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (replay.hash != canonical.hash || replay.permit.binding != binding) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            authorizeScope(replay.permit.source, current)
            store.lock(binding.authorizationId)
            store.read(binding.authorizationId)
            return DeploymentAuthorizationRef(binding.authorizationId, binding.operationId, 0)
        }
        val permit = RmaDeploymentPermit(binding, source, false)
        validateSource(permit, current)
        store.mint(RmaDeploymentMint(permit, metadata.idempotencyKey, canonical.hash))
        store.read(binding.authorizationId)
        return DeploymentAuthorizationRef(binding.authorizationId, binding.operationId, 0)
    }

    fun consume(request: ConsumeDeploymentRequest, metadata: WarehouseMutationMetadata): DeploymentConsumption {
        key(metadata)
        if (request.expectedRevision != 0L || request.installationPayload.length > 8192 || request.observation != null)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val preview = store.preview(request.authorizationId)
        if (preview.binding.customerId != request.customerId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        workOrders.lockAndValidate(DeploymentValidationContext(preview.binding, current.fence, cutover))
        val permit = store.lock(request.authorizationId)
        if (permit.binding != preview.binding) masterFailure(WarehouseErrorCode.STALE_REVISION)
        authorizeScope(permit.source, current)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        if (permit.consumed) {
            store.read(request.authorizationId)
            val original = deployments.result(request.authorizationId)
            if (original.key != metadata.idempotencyKey || original.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            return original.consumption
        }
        validateSource(permit, current)
        store.read(request.authorizationId)
        val binding = permit.binding
        val started = clock.now()
        val assignment = AssetAssignmentRef(binding.operationId, binding.assetId, binding.customerId, binding.workOrderId, null,
            0, DeploymentPurpose.RETURN_CUSTOMER_RMA, permit.source.provenance, AssetOwnershipMode.SALE, AssetLegalOwner.CUSTOMER,
            AssetHandoverState.PENDING, null, started, null, binding.previousAssignmentId, false)
        val result = DeploymentConsumption(assignment, binding.operationId, permit.source.serial, permit.source.model, permit.source.createsOnu)
        val operation = PostingOperation(binding.operationId, "warehouse.deployment.consume", metadata.idempotencyKey, binding.actorId,
            binding.authorizationId, "workorder:${binding.workOrderId}", canonical.hash, "INSTALL", 201, mapper.writeValueAsString(result),
            current.fence.epoch, started)
        val source = permit.source.custody
        val line = documents.create(binding, source, permit.source.handoverId)
        val installed = source.copy(locationId = deployments.installedLocation(), custodianId = binding.customerId, custodianKind = OwnerKind.CUSTOMER)
        val quantity = StockQuantity.of(1, StockUnit.EA)
        posting.post(WarehousePost(binding.operationId, 0, "POSTED", operation, MovementKind.DEPLOY, "Return inspected RMA to original customer",
            listOf(PostingLeg(LegDirection.OUT, source, quantity, line, InventoryStatus.ISSUED),
                PostingLeg(LegDirection.IN, installed, quantity, line, InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED))), cutover)
        operations.storeIdentity(binding.operationId, canonical.json, current.fence.identity.sessionId)
        assignments.append(NewAssetAssignment(assignment.assignmentId, binding.assetId, binding.customerId, binding.workOrderId,
            null, DeploymentPurpose.RETURN_CUSTOMER_RMA, AssetOwnershipMode.SALE, AssetLegalOwner.CUSTOMER, permit.source.provenance,
            binding.actorId, started, binding.previousAssignmentId))
        deployments.recordConsumption(binding, DeploymentResult(result, metadata.idempotencyKey, canonical.hash))
        return result
    }

    private fun validateSource(permit: RmaDeploymentPermit, current: CurrentAuthority) {
        if (permit.source.custody.custodianId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        authorizeScope(permit.source, current)
        store.lockSource(permit.source)
        if (deployments.useRevision(permit.binding.workOrderId) != permit.binding.revisions.useRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
    }
    private fun authorizeScope(source: RmaDeploymentSource, current: CurrentAuthority) {
        masters.lockTopology()
        val view = handovers.get(source.handoverId).view
        val scope = scopes.currentUnderFence(current.fence)
        listOf(view.sourceLocationId, view.transitLocationId, view.technicianLocationId).distinct().sortedBy(UUID::toString)
            .forEach { locations.authorizeLocation(it, current, scope) }
    }
    private fun key(metadata: WarehouseMutationMetadata) {
        receiptKey(metadata.idempotencyKey)
        if (metadata.idempotencyKey.length > 200) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
}
