package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class InventoryAssetTitleService(private val cutovers: InventoryTenantCutoverApi, private val authorities: CurrentAuthorityApi,
    private val titles: AssetTitleStore, private val corrections: AssetTitleCorrectionStore,
    private val handovers: AssetHandoverStore, private val workOrders: AssetHandoverWorkOrderPort,
    private val customers: AssetHandoverCustomerPort, private val access: WarehousePolicyAccess,
    private val masters: WarehouseMasterStore, private val clock: WarehousePolicyPersistence) : InventoryAssetTitleApi {
    private val mapper = jacksonObjectMapper()

    override fun requestCorrection(request: AssetTitleCorrectionRequest, metadata: WarehouseMutationMetadata): AssetTitleCorrectionRef {
        receiptKey(metadata.idempotencyKey)
        if (request.reason.isBlank() || request.reason.length > 500 || request.targetOwner == AssetLegalOwner.UNKNOWN ||
            request.expectedAssignmentRevision < 0 || request.expectedTitleRevision < 0) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authorities.lockCurrent()
        access.permission(current, "inventory.approval.request")
        access.permission(current, "inventory.approval.view")
        val handover = titles.handover(request.sourceHandoverId)
        if (handover.assignment.assignmentId != request.assignmentId) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val revision = workOrders.lockTitle(handover.assignment.workOrderId, current.fence)
        masters.lockTopology()
        access.location(handover.position.dimension.locationId, current)
        titles.lockAssignment(request.assignmentId)
        handovers.assertValid(handover)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        corrections.replay(metadata.idempotencyKey, canonical.hash, current.fence.identity.userId)?.let { return it.reference() }
        val source = titles.current(request.assignmentId)
        if (source.assignmentRevision != request.expectedAssignmentRevision || source.titleRevision != request.expectedTitleRevision)
            masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (source.legalOwner == request.targetOwner) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val position = titles.position(source.assetId)
        customers.lock(source.customerId, source.assignmentId)
        val evidence = workOrders.signature(source.workOrderId, request.evidenceId)
        val id = UUID.randomUUID()
        val snapshot = AssetTitleCorrectionSnapshot(id, "TITLE-$id", handover, source, position, request.targetOwner,
            request.reason, evidence, current.fence.identity.userId, revision, current.fence.epoch, cutover.snapshot.epoch, clock.now())
        corrections.insert(snapshot, metadata.idempotencyKey, canonical.hash)
        return snapshot.reference()
    }

    override fun forCustomer(customerId: UUID): List<CurrentAssetOwnership> {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authorities.lockCurrent()
        access.permission(current, "customer.onu.view")
        return titles.assignments(customerId).map { (assignment, workOrder) ->
            workOrders.lockTitle(workOrder, current.fence)
            titles.lockAssignment(assignment)
            val title = titles.current(assignment)
            val customer = customers.lock(customerId, assignment)
            val ceased = customer.status == "TERMINATED"
            title.copy(serviceCeased = ceased, recoveryDue = ceased && title.recoveryRequired)
        }
    }
}
