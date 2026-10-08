package com.duluin.ftth.fulfillment

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.DeliveryAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.FulfillmentApproved
import com.duluin.ftth.workorder.WorkOrderSettlementApi
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FulfillmentApprovalService(private val store: FulfillmentApprovalStore, private val workOrders: WorkOrderSettlementApi,
    private val inventory: InventorySettlementApi, private val authority: CurrentAuthorityApi,
    private val deliveryAuthority: DeliveryAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val orders: com.duluin.ftth.order.OrderApi, private val visits: com.duluin.ftth.fieldservice.FieldServiceApi,
    private val customers: com.duluin.ftth.customer.CustomerApi, private val bng: com.duluin.ftth.bng.BngProvisioningApi,
    private val customerLocks: com.duluin.ftth.customer.CustomerFulfillmentLockApi,
    private val bngOwner: com.duluin.ftth.bng.BngFulfillmentApi) {

    fun freeze(event: FulfillmentApproved): FulfillmentRequest = freeze(event.workOrderId, false)

    fun freezeReference(id: UUID): FulfillmentRequest = freeze(id, true)

    private fun freeze(id: UUID, reference: Boolean): FulfillmentRequest {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, if (reference)
            WarehouseOperationClass.REFERENCE_WORK_ORDER else WarehouseOperationClass.LEGACY_FULFILLMENT)
        val current = authority.lockCurrent()
        val completed = if (reference) workOrders.lockCompleted(id, current) else null
        val approved = completed?.workOrder ?: workOrders.lockApproved(id, current)
        val workOrder = approved.material
        val key = "${workOrder.workOrderId}:${workOrder.workOrderRevision}:${approved.proofHash}"
        store.forWorkOrder(workOrder.workOrderId)?.let {
            if (it.snapshot.workOrder != approved || it.snapshot.referenceCompletion != completed?.completion)
                throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_REVISION, "FULFILLMENT_SNAPSHOT_STALE"))
            return it.request
        }
        val linkedVisits = visits.visitsByWorkOrder(workOrder.workOrderId)
        if (linkedVisits.size > 1) fail("VISIT_LINK_NOT_UNIQUE")
        val visit = linkedVisits.singleOrNull()?.let { visits.lockFulfillment(it.id,workOrder.workOrderId) }
        if (visit != null && visit.technicianId !in workOrder.activeAssigneeIds) fail("FULFILLMENT_VISIT_BINDING")
        val orderBinding = workOrder.orderId?.let { orders.lockFulfillment(com.duluin.ftth.order.OrderFulfillmentTarget(it,
            workOrder.customerId ?: fail("FULFILLMENT_ORDER_BINDING"))) }
        val orderRevision = orderBinding?.revision
        val customerBinding = workOrder.customerId?.let { customerLocks.lock(it, workOrder.subscriptionId) }
        val subscription = workOrder.subscriptionId?.let { customers.findSubscription(it) ?: fail("SUBSCRIPTION_NOT_FOUND") }
        val bngBinding = subscription?.let { bngOwner.lock(it.id,workOrder.customerId ?: fail("FULFILLMENT_BNG_BINDING")) }
        val bngAccessId = bngBinding?.accessId
        val effects = buildSet {
            add(FulfillmentEffectType.INVENTORY)
            add(FulfillmentEffectType.WORK_ORDER)
            if (orderRevision != null) add(FulfillmentEffectType.ORDER)
            if (visit != null) add(FulfillmentEffectType.VISIT)
            if (subscription != null && workOrder.customerId != null && workOrder.action.name in setOf("INSTALL", "REMOVE")) {
                add(FulfillmentEffectType.SUBSCRIPTION)
                if (bngAccessId != null) add(FulfillmentEffectType.PROVISIONING)
            }
        }
        val context = MaterialPlanningContext(workOrder.workOrderId, workOrder.code, workOrder.workType, workOrder.action.name,
            workOrder.workOrderRevision, workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
        val material = if (reference) null else inventory.freeze(context)
        return store.save(FulfillmentApprovalSnapshot(UUID.randomUUID(), current.fence.identity, cutover.snapshot.epoch,
            approved, material, effects, orderRevision, visit, subscription, bngAccessId, customerBinding, orderBinding, bngBinding, completed?.completion), key).request
    }

    fun lock(request: FulfillmentRequest) {
        val frozen = store.find(request.namespace, request.operationKey) ?: return
        deliveryFence(frozen.snapshot.cutoverEpoch, frozen.snapshot.referenceCompletion != null)
        val current = deliveryAuthority.lockActor(frozen.snapshot.identity)
        val completed = if (frozen.snapshot.referenceCompletion != null)
            workOrders.lockCompleted(frozen.snapshot.workOrder.material.workOrderId, current) else null
        val workOrder = completed?.workOrder ?: workOrders.lockApproved(frozen.snapshot.workOrder.material.workOrderId, current)
        store.lock(frozen.snapshot.id)
        if (request != frozen.request || workOrder != frozen.snapshot.workOrder || completed?.completion != frozen.snapshot.referenceCompletion)
            fail("FULFILLMENT_SNAPSHOT_STALE")
        store.validateOwners(frozen.snapshot.id)
    }

    fun preflight(request: FulfillmentRequest) {
        val frozen = require(request)
        val snapshot = frozen.snapshot
        val workOrder = snapshot.workOrder.material
        if (workOrder.customerId?.let { customerLocks.lock(it, workOrder.subscriptionId) } != snapshot.customerBinding)
            fail("FULFILLMENT_CUSTOMER_STALE")
        snapshot.visit?.let { if (visits.lockFulfillment(it.id,workOrder.workOrderId) != it) fail("FULFILLMENT_VISIT_STALE") }
        val order = workOrder.orderId?.let { orders.lockFulfillment(com.duluin.ftth.order.OrderFulfillmentTarget(it,
            workOrder.customerId ?: fail("FULFILLMENT_ORDER_BINDING"),snapshot.orderRevision)) }
        if (order != snapshot.orderBinding ||
            visits.visitsByWorkOrder(workOrder.workOrderId) != listOfNotNull(snapshot.visit) ||
            workOrder.subscriptionId?.let(customers::findSubscription) != snapshot.subscription ||
            snapshot.subscription?.let { bngOwner.lock(it.id,workOrder.customerId ?: fail("FULFILLMENT_BNG_BINDING")) } != snapshot.bngBinding)
            fail("FULFILLMENT_LINK_STALE")
        if (snapshot.referenceCompletion != null) store.validateReference(snapshot.id)
        else if (inventory.freeze(context(snapshot)) != snapshot.material) fail("FULFILLMENT_USAGE_STALE")
    }

    fun verify(request: FulfillmentRequest) {
        val snapshot = require(request).snapshot
        if (snapshot.referenceCompletion != null) store.verifyReference(snapshot.id)
        else inventory.verify(context(snapshot), MaterialSettlementApproval(snapshot.id, request.canonicalHash, requireNotNull(snapshot.material)))
    }

    fun currentAuthority(request: FulfillmentRequest): com.duluin.ftth.common.security.AuthorityFence =
        deliveryAuthority.lockActor(require(request).snapshot.identity).fence

    fun require(request: FulfillmentRequest): FrozenFulfillment {
        val frozen = store.find(request.namespace, request.operationKey) ?: fail("FULFILLMENT_SNAPSHOT_REQUIRED")
        if (frozen.request != request) fail("FULFILLMENT_SNAPSHOT_MISMATCH")
        return frozen
    }

    private fun context(snapshot: FulfillmentApprovalSnapshot): MaterialPlanningContext {
        val cutover = deliveryFence(snapshot.cutoverEpoch)
        val current = deliveryAuthority.lockActor(snapshot.identity)
        val workOrder = snapshot.workOrder.material
        return MaterialPlanningContext(workOrder.workOrderId, workOrder.code, workOrder.workType, workOrder.action.name,
            workOrder.workOrderRevision, workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
    }

    private fun deliveryFence(expectedEpoch: Long, reference: Boolean = false): TenantCutoverFence {
        val fence = cutovers.lockForCommand(cutovers.read().epoch, if (reference)
            WarehouseOperationClass.REFERENCE_WORK_ORDER else WarehouseOperationClass.LEGACY_FULFILLMENT)
        val snapshot = fence.snapshot
        if (snapshot.epoch != expectedEpoch && !(!reference && snapshot.workflow == WarehouseWorkflow.DRAINING &&
                snapshot.state == WarehouseCutoverState.ENFORCED && snapshot.drainingFromEpoch == expectedEpoch &&
                expectedEpoch < Long.MAX_VALUE && snapshot.epoch == expectedEpoch + 1))
            fail("FULFILLMENT_SNAPSHOT_STALE")
        return fence
    }

    private fun fail(code: String): Nothing = throw FulfillmentExecutionFailure.ReconciliationRequired(code)
}
