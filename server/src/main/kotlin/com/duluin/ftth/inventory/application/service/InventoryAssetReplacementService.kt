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
class InventoryAssetReplacementService(private val cutovers: InventoryTenantCutoverApi, private val authorities: CurrentAuthorityApi,
    private val workOrders: AssetRemovalWorkOrderPort, private val evidence: AssetHandoverWorkOrderPort,
    private val deployments: DeploymentStore, private val deploymentOwner: InventoryDeploymentService,
    private val store: AssetRemovalStore, private val titles: AssetTitleStore, private val assignments: AssetAssignmentStore,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore, private val clock: MaterialPlanningStore,
    private val locations: WarehouseReceiptService, private val scopes: InventoryWarehouseScopeApi) : InventoryAssetReplacementApi {
    private val mapper = jacksonObjectMapper()

    override fun lockTopology(context: AssetTopologyContext): CurrentAssetOwnership {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authorities.lockCurrent()
        val revision = workOrders.lock(AssetRemovalWorkOrderRequest(context.workOrderId, context.customerId, DeploymentPurpose.REPLACE), current.fence)
        if (revision != context.expectedWorkOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val ownership = store.lock(context.assignmentId, null)
        if (ownership.customerId != context.customerId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return ownership
    }

    @Transactional(timeout = 30)
    override fun authorizeOutcome(customerId: UUID, operationId: UUID): AssetRemovalResult {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authorities.lockCurrent()
        val outcome = store.outcome(operationId)
        if (outcome.actorId != current.fence.identity.userId || outcome.result.customerId != customerId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        val result = outcome.result
        workOrders.lock(AssetRemovalWorkOrderRequest(result.workOrderId, customerId,
            if (result.replacement == null) DeploymentPurpose.REMOVE else DeploymentPurpose.REPLACE), current.fence)
        locations.authorizeLocation(store.recoveryLocation(), current, scopes.currentUnderFence(current.fence))
        store.assertValid(operationId)
        return result
    }

    override fun replace(customerId: UUID, request: ReplacePhysicalAssetRequest, metadata: WarehouseMutationMetadata): AssetRemovalResult {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK).assertHeld()
        authorities.lockCurrent()
        val permit = deployments.preview(request.authorizationId)
        if (permit.binding.purpose != DeploymentPurpose.REPLACE || permit.binding.customerId != customerId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val assignment = permit.binding.previousAssignmentId ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return execute(RemovalCommand(customerId, RemovePhysicalAssetRequest(assignment, permit.binding.workOrderId,
            request.expectedAssignmentRevision, request.expectedTitleRevision, request.evidenceId), request), metadata)
    }

    override fun remove(customerId: UUID, request: RemovePhysicalAssetRequest, metadata: WarehouseMutationMetadata): AssetRemovalResult =
        execute(RemovalCommand(customerId, request, null), metadata)

    private data class RemovalCommand(val customerId: UUID, val removal: RemovePhysicalAssetRequest, val replacement: ReplacePhysicalAssetRequest?)

    private fun execute(command: RemovalCommand, metadata: WarehouseMutationMetadata): AssetRemovalResult {
        receiptKey(metadata.idempotencyKey)
        val request = command.removal
        if (metadata.idempotencyKey.length > 200 || request.expectedRevision < 0 || request.expectedTitleRevision < 0)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        val purpose = if (command.replacement == null) DeploymentPurpose.REMOVE else DeploymentPurpose.REPLACE
        val revision = workOrders.lock(AssetRemovalWorkOrderRequest(request.workOrderId, command.customerId, purpose), current.fence)
        val recovery = store.recoveryLocation()
        locations.authorizeLocation(recovery, current, scopes.currentUnderFence(current.fence))
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(command))
        store.lockKey(metadata.idempotencyKey)
        store.replay(metadata.idempotencyKey)?.let { prior ->
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.hash != canonical.hash || prior.result.customerId != command.customerId) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            store.assertValid(prior.result.operationId)
            return prior.result
        }
        val permit = command.replacement?.let { deployments.preview(it.authorizationId) }
        val ownership = store.lock(request.assignmentId, permit?.binding?.assetId)
        if (ownership.customerId != command.customerId || ownership.assignmentRevision != request.expectedRevision ||
            ownership.titleRevision != request.expectedTitleRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val position = titles.position(ownership.assetId)
        if (position.dimension.custodianId != command.customerId || position.dimension.legalOwner != ownership.legalOwner)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val signature = evidence.signature(request.workOrderId, request.evidenceId)
        val replacement = command.replacement?.let {
            deploymentOwner.consumeReplacement(ConsumeDeploymentRequest(it.authorizationId, it.expectedRevision, command.customerId,
                it.installationPayload), WarehouseMutationMetadata("replacement:${metadata.idempotencyKey}"))
        }
        val removedAt = replacement?.assignment?.startedAt ?: clock.now()
        val result = AssetRemovalResult(UUID.randomUUID(), ownership.assignmentId, ownership.assetId, command.customerId,
            request.workOrderId, removedAt, ownership.legalOwner, replacement)
        val record = AssetRemovalRecord(result, ownership, position, recovery, current.fence.identity.userId, permit?.binding?.authorizationId,
            signature, revision, current.fence.epoch, cutover.snapshot.epoch, metadata.idempotencyKey, canonical.hash)
        store.append(record)
        val operation = PostingOperation(result.operationId, "warehouse.asset.remove", metadata.idempotencyKey, record.actorId,
            result.assignmentId, "workorder:${result.workOrderId}", canonical.hash, purpose.name, 200,
            mapper.writeValueAsString(result), current.fence.epoch, removedAt)
        val quantity = StockQuantity.of(1, StockUnit.EA)
        val destination = position.dimension.copy(locationId = recovery, custodianId = record.actorId,
            custodianKind = OwnerKind.TRANSIT, condition = WarehouseCondition.QUARANTINE)
        posting.post(WarehousePost(result.operationId, 0, "POSTED", operation, MovementKind.RETURN, "Witnessed physical asset removal",
            listOf(PostingLeg(LegDirection.OUT, position.dimension, quantity, result.operationId, InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED),
                PostingLeg(LegDirection.IN, destination, quantity, result.operationId, InventoryStatus.QUARANTINE))), cutover)
        operations.storeIdentity(result.operationId, canonical.json, current.fence.identity.sessionId)
        assignments.close(AssetAssignmentClosure(result.assignmentId, ownership.assignmentRevision, removedAt))
        return result
    }
}
