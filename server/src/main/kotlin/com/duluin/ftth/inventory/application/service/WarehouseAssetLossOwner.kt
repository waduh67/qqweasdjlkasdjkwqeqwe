package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class WarehouseAssetLossOwner(private val store: WarehouseAssetLossStore, private val titles: AssetTitleStore,
    private val workOrders: AssetHandoverWorkOrderPort, private val masters: WarehouseMasterStore,
    private val access: WarehousePolicyAccess, private val approvalGuards: WarehouseDispositionEffectStore,
    private val posting: WarehousePosting, private val operations: WarehouseOperationStore,
    private val assignments: AssetAssignmentStore, private val customers: AssetLossCustomerPort,
    private val provisioning: AssetLossProvisioningPort) : WarehouseApprovalOwner, WarehouseApprovalSourceLock {
    override val kind = "ASSET_LOSS"

    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val request = store.find(sourceDocumentId) ?: return
        workOrders.lockTitle(request.ownership.workOrderId, current.fence)
        masters.lockTopology()
        listOf(request.position.dimension.locationId, request.input.destinationLocationId).distinct().sortedBy(UUID::toString)
            .forEach { access.location(it, current) }
        titles.lockAssignment(request.input.assignmentId)
    }

    override fun validate(source: ApprovalSourceState, id: UUID) {
        val request = store.get(id)
        if (source.kind != kind || source.state != "DRAFT" || source.revision != 0L || source.disposition != null ||
            source.requester != request.actorId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    override fun prepare(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt, current: CurrentAuthority): ReceiptPostingApproval {
        val guard = approvalGuards.guard(record, attempt, ApprovalPostingKind.ASSET_LOSS)
        if (!store.sourceMatches(attempt.sourceDocumentId)) throw ApprovalPostingStopped(guard, WarehouseApprovalStatus.STALE)
        approvalGuards.assertApproval(guard)
        return guard
    }

    override fun apply(record: WarehouseApprovalRecord, operation: PostingOperation, current: CurrentAuthority,
        cutover: TenantCutoverFence, approval: ReceiptPostingApproval) {
        val request = store.get(record.snapshot.evaluation.sourceDocumentId)
        store.beginEffect(request, record.id, operation)
        val source = request.position.dimension
        val target = source.copy(locationId = request.input.destinationLocationId, custodianId = request.input.destinationLocationId,
            custodianKind = OwnerKind.LOST)
        val quantity = StockQuantity.of(1, StockUnit.EA)
        posting.post(WarehousePost(request.id, 0, "POSTED", operation, MovementKind.LOSS, request.input.reason,
            listOf(PostingLeg(LegDirection.OUT, source, quantity, request.id, InventoryStatus.CUSTOMER_INSTALLED, PostingEndpoint.CUSTOMER_INSTALLED),
                PostingLeg(LegDirection.IN, target, quantity, request.id, InventoryStatus.LOST)), approval = approval), cutover)
        operations.storeIdentity(operation.id, record.snapshot.source, current.fence.identity.sessionId)
        assignments.close(AssetAssignmentClosure(request.input.assignmentId, request.ownership.assignmentRevision, operation.recordedAt))
        val closure = AssetLossClosure(request.id, operation.id, request.input.assignmentId, request.ownership.assetId,
            request.ownership.customerId, request.ownership.workOrderId, operation.recordedAt)
        val onu = customers.retire(closure)
        provisioning.enqueue(closure, onu)
    }
}
