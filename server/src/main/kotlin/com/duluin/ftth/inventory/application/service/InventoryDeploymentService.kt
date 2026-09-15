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
class InventoryDeploymentService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val workOrders: InventoryWorkOrderValidationPort, private val store: DeploymentStore,
    private val plans: MaterialPlanningStore, private val sources: MaterialUsageStore,
    private val scopes: InventoryWarehouseScopeApi, private val masters: WarehouseMasterStore,
    private val locations: WarehouseReceiptService, private val assignments: AssetAssignmentStore,
    private val documents: DeploymentPostingStore, private val posting: WarehousePosting,
    private val operations: WarehouseOperationStore, private val handovers: AssetHandoverService) : InventoryDeploymentApi {
    private val mapper = jacksonObjectMapper()

    override fun authorize(workOrderId: UUID, request: DeploymentIntentRequest, metadata: WarehouseMutationMetadata): DeploymentAuthorizationRef {
        key(metadata)
        if (request.expectedRevision < 0 || request.purpose != DeploymentPurpose.INSTALL || request.previousAssignmentId != null || request.repairCaseId != null)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("workOrderId" to workOrderId, "request" to request)))
        val prior = store.findMint(metadata.idempotencyKey)
        val source = prior?.permit?.source ?: store.source(request.issueLineId ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED))
        if (source.workOrderId != workOrderId || source.custody.stockIdentityId != request.assetId || source.issueLineId != request.issueLineId)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val binding = prior?.permit?.binding ?: DeploymentBinding(UUID.randomUUID(), UUID.randomUUID(), current.fence.identity.tenantId,
            current.fence.identity.userId, request.assetId, request.assetId, request.issueLineId, workOrderId, source.customerId,
            request.purpose, request.ownershipMode, MaterialRevisions(request.expectedRevision, source.planRevision, store.useRevision(workOrderId), 0),
            source.issueRevision, source.assetRevision, current.fence.epoch, cutover.snapshot.epoch, null, null, null)
        workOrders.lockAndValidate(DeploymentValidationContext(binding, current.fence, cutover))
        store.lockKey(metadata.idempotencyKey)
        val lockedPrior = store.findMint(metadata.idempotencyKey)
        if (lockedPrior != null) {
            if (lockedPrior.permit.binding.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (lockedPrior.hash != canonical.hash || lockedPrior.permit.binding != binding) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            store.lock(binding.authorizationId)
            store.read(binding.authorizationId)
            authorizeScope(lockedPrior.permit.source, current)
            return DeploymentAuthorizationRef(binding.authorizationId, binding.operationId, 0)
        }
        val permit = DeploymentPermit(binding, source, false)
        validateSource(permit, current)
        store.mint(DeploymentMint(permit, metadata.idempotencyKey, canonical.hash))
        store.read(binding.authorizationId)
        return DeploymentAuthorizationRef(binding.authorizationId, binding.operationId, 0)
    }

    override fun consume(request: ConsumeDeploymentRequest, metadata: WarehouseMutationMetadata): DeploymentConsumption {
        key(metadata)
        if (request.expectedRevision != 0L || request.installationPayload.length > 8192) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val preview = store.preview(request.authorizationId)
        if (preview.binding.customerId != request.customerId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        workOrders.lockAndValidate(DeploymentValidationContext(preview.binding, current.fence, cutover))
        val locked = store.lock(request.authorizationId)
        if (locked.binding != preview.binding) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        authorizeScope(locked.source, current)
        if (locked.consumed) {
            store.read(request.authorizationId)
            val original = store.result(request.authorizationId)
            if (original.key != metadata.idempotencyKey || original.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            return original.consumption
        }
        validateSource(locked, current)
        val permit = store.read(request.authorizationId)
        val binding = permit.binding
        val started = plans.now()
        val assignment = AssetAssignmentRef(binding.operationId, binding.assetId, binding.customerId, binding.workOrderId,
            binding.issueLineId, 0, binding.purpose, permit.source.provenance, binding.ownershipMode, AssetLegalOwner.ISP,
            AssetHandoverState.PENDING, null, started, null, null, binding.ownershipMode == AssetOwnershipMode.LOAN)
        val consumption = DeploymentConsumption(assignment, binding.operationId, permit.source.serial, permit.source.model, permit.source.createsOnu)
        val operation = PostingOperation(binding.operationId, "warehouse.deployment.consume", metadata.idempotencyKey, binding.actorId,
            binding.authorizationId, "workorder:${binding.workOrderId}", canonical.hash, "INSTALL", 201, mapper.writeValueAsString(consumption),
            current.fence.epoch, started)
        val line = documents.create(permit)
        val source = permit.source.custody
        val installed = source.copy(locationId = store.installedLocation(), custodianId = binding.customerId, custodianKind = OwnerKind.CUSTOMER)
        val quantity = StockQuantity.of(1, StockUnit.EA)
        posting.post(WarehousePost(binding.operationId, 0, "POSTED", operation, MovementKind.DEPLOY, "Authorized customer installation",
            listOf(PostingLeg(LegDirection.OUT, source, quantity, line, InventoryStatus.ISSUED),
                PostingLeg(LegDirection.IN, installed, quantity, line, InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED))), cutover)
        operations.storeIdentity(binding.operationId, canonical.json, current.fence.identity.sessionId)
        assignments.append(NewAssetAssignment(assignment.assignmentId, binding.assetId, binding.customerId, binding.workOrderId,
            binding.issueLineId, binding.purpose, binding.ownershipMode, AssetLegalOwner.ISP, permit.source.provenance, binding.actorId, started))
        store.consume(permit, DeploymentResult(consumption, metadata.idempotencyKey, canonical.hash))
        return consumption
    }

    private fun validateSource(permit: DeploymentPermit, current: CurrentAuthority) {
        val binding = permit.binding
        val source = permit.source
        if (source.custody.custodianId != binding.actorId || source.custody.custodianKind != OwnerKind.TECHNICIAN ||
            source.custody.condition != WarehouseCondition.SERVICEABLE || source.custody.legalOwner != AssetLegalOwner.ISP)
            masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        authorizeScope(source, current)
        sources.lockSources(listOf(source.receiptId))
        store.lockAsset(source)
        store.assertOwnership(source, binding.ownershipMode)
        if (store.source(source.issueLineId) != source || store.useRevision(binding.workOrderId) != binding.revisions.useRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        val plan = plans.current(binding.workOrderId) ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (plan.state != "SUBMITTED" || plan.plan.id != source.planId || plan.plan.planRevision != binding.revisions.planRevision ||
            plan.plan.customerId != binding.customerId || plan.plan.materialMode != MaterialMode.MATERIAL_REQUIRED ||
            plan.plan.action != "INSTALL" || plan.plan.workType != "PSB") masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    private fun authorizeScope(source: DeploymentSource, current: CurrentAuthority) {
        masters.lockTopology()
        locations.authorizeLocation(source.custody.locationId, current, scopes.currentUnderFence(current.fence))
    }
    private fun key(metadata: WarehouseMutationMetadata) {
        receiptKey(metadata.idempotencyKey)
        if (metadata.idempotencyKey.length > 200) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
    override fun acceptHandover(request: AcceptAssetHandoverRequest, metadata: WarehouseMutationMetadata): AssetAssignmentRef =
        handovers.accept(request, metadata)
    @Transactional(timeout = 30)
    override fun assignmentHistory(assetId: UUID, page: WarehousePageRequest): WarehousePage<AssetAssignmentRef> {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        receiptPermission(current, "customer.onu.view")
        val rows = store.history(assetId).map { permit ->
            if (permit.binding.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            authorizeScope(permit.source, current)
            store.result(permit.binding.authorizationId).consumption.assignment
        }
        val offset = page.page.toLong() * page.size
        return WarehousePage(if (offset >= rows.size) emptyList() else rows.drop(offset.toInt()).take(page.size), page.page, page.size, rows.size.toLong())
    }
}
