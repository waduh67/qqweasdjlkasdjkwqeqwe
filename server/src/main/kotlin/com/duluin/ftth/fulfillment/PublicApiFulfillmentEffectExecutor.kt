package com.duluin.ftth.fulfillment

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.bng.BngProvisioningApi
import com.duluin.ftth.fieldservice.FieldServiceApi
import com.duluin.ftth.fieldservice.VisitFulfillmentCommand
import com.duluin.ftth.order.OrderApi
import com.duluin.ftth.order.OrderFulfillmentCommand
import com.duluin.ftth.order.OrderTransition
import com.duluin.ftth.workorder.WorkOrderFulfillmentApi
import com.duluin.ftth.workorder.WorkOrderFulfillmentCommand
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class PublicApiFulfillmentEffectExecutor(private val customer: CustomerApi, private val bng: BngProvisioningApi,
    private val orders: OrderApi, private val workOrders: WorkOrderFulfillmentApi, private val fieldService: FieldServiceApi,
    private val approvals: FulfillmentApprovalService,
    private val customerOwner: com.duluin.ftth.customer.CustomerFulfillmentApi,
    private val bngOwner: com.duluin.ftth.bng.BngFulfillmentApi) : FulfillmentEffectExecutor {
    override fun lock(request: FulfillmentRequest) { approvals.lock(request) }

    override fun preflight(request: FulfillmentRequest) {
        if (request.requiredEffects.isEmpty()) fail("FULFILLMENT_APPLICABILITY_REQUIRED")
        when (request.source) {
            FulfillmentSource.WORK_ORDER -> approvals.preflight(request)
            FulfillmentSource.MIGRATION -> {
                val subscription = request.subscriptionId ?: fail("SUBSCRIPTION_LINK_NOT_FOUND")
                if (customer.findSubscription(subscription) == null) fail("SUBSCRIPTION_NOT_FOUND")
                if (request.requiredEffects != setOf(FulfillmentEffectType.SUBSCRIPTION, FulfillmentEffectType.PROVISIONING))
                    fail("MIGRATION_EFFECTS_UNSUPPORTED")
            }
        }
    }

    override fun apply(request: FulfillmentRequest) {
        preflight(request)
        request.requiredEffects.forEach { apply(request, it) }
    }

    override fun apply(request: FulfillmentRequest, effect: FulfillmentEffectType) {
        if (effect !in request.requiredEffects) fail("FULFILLMENT_EFFECT_NOT_APPLICABLE")
        when (effect) {
            FulfillmentEffectType.INVENTORY -> approvals.verify(request)
            FulfillmentEffectType.WORK_ORDER -> workOrders.recordFulfillmentResult(WorkOrderFulfillmentCommand(request.tenantId,
                request.workOrderId ?: fail("WORK_ORDER_LINK_NOT_FOUND"), request.namespace, request.operationKey, request.canonicalHash,
                request.source.name, "APPLIED"))
            FulfillmentEffectType.SUBSCRIPTION -> {
                val subscription = request.subscriptionId ?: fail("SUBSCRIPTION_LINK_NOT_FOUND")
                when (request.source) {
                    FulfillmentSource.WORK_ORDER -> customerOwner.apply(com.duluin.ftth.customer.CustomerFulfillmentCommand(reference(request),
                        approvals.require(request).snapshot.workOrder.material.customerId ?: fail("SUBSCRIPTION_LINK_NOT_FOUND"),subscription,
                        if (request.workOrderKind == "DISMANTLE") com.duluin.ftth.customer.CustomerFulfillmentAction.TERMINATE
                        else com.duluin.ftth.customer.CustomerFulfillmentAction.ACTIVATE),approvals.currentAuthority(request))
                    FulfillmentSource.MIGRATION -> customer.activateForInstallation(subscription)
                }
            }
            FulfillmentEffectType.PROVISIONING -> {
                val subscriptionId = request.subscriptionId ?: fail("SUBSCRIPTION_LINK_NOT_FOUND")
                val action = if (request.workOrderKind == "DISMANTLE") com.duluin.ftth.bng.BngFulfillmentAction.TERMINATE
                    else com.duluin.ftth.bng.BngFulfillmentAction.ACTIVATE
                when (request.source) {
                    FulfillmentSource.WORK_ORDER -> bngOwner.apply(com.duluin.ftth.bng.BngFulfillmentCommand(reference(request),
                        approvals.require(request).snapshot.bngBinding ?: fail("FULFILLMENT_BNG_BINDING"),action),approvals.currentAuthority(request))
                    FulfillmentSource.MIGRATION -> bng.applyFulfillment(subscriptionId,action)
                }
                val access = bng.findAccess(subscriptionId)
                val expected = if (request.workOrderKind == "DISMANTLE") "TERMINATED" else "ACTIVE"
                if (access?.accountStatus != expected) fail("BNG_FULFILLMENT_NOT_CONFIRMED")
            }
            FulfillmentEffectType.ORDER -> {
                val frozen = approvals.require(request).snapshot
                orders.applyFulfillment(OrderFulfillmentCommand(request.tenantId, request.orderId ?: fail("ORDER_LINK_NOT_FOUND"),
                    OrderTransition.FULFILL, frozen.orderRevision ?: fail("ORDER_REVISION_NOT_FOUND"), request.namespace,
                    request.operationKey, request.canonicalHash, frozen.orderBinding?.customerId ?: fail("FULFILLMENT_ORDER_BINDING"),reference(request)), approvals.currentAuthority(request))
            }
            FulfillmentEffectType.VISIT -> {
                val visit = approvals.require(request).snapshot.visit ?: fail("VISIT_LINK_NOT_FOUND")
                fieldService.applyFulfillment(VisitFulfillmentCommand(request.tenantId, visit.id, visit.technicianId, visit.revision,
                    request.namespace, request.operationKey, request.canonicalHash, Instant.now(),reference(request)))
            }
        }
    }

    private fun fail(code: String): Nothing = throw FulfillmentExecutionFailure.ReconciliationRequired(code)
    private fun reference(request: FulfillmentRequest) = com.duluin.ftth.common.domain.FulfillmentEffectReference(
        approvals.require(request).snapshot.id,request.namespace,request.operationKey,request.canonicalHash)
}
