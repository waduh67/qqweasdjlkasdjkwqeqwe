package com.karuhun.ftth.fulfillment

import com.karuhun.ftth.common.security.SessionIdentity
import com.karuhun.ftth.inventory.MaterialSettlementSource
import com.karuhun.ftth.workorder.ApprovedWorkOrderContext
import java.util.UUID

data class FulfillmentApprovalSnapshot(
    val id: UUID,
    val identity: SessionIdentity,
    val cutoverEpoch: Long,
    val workOrder: ApprovedWorkOrderContext,
    val material: MaterialSettlementSource?,
    val effects: Set<FulfillmentEffectType>,
    val orderRevision: Long?,
    val visit: com.karuhun.ftth.fieldservice.VisitRef?,
    val subscription: com.karuhun.ftth.customer.SubscriptionRef?,
    val bngAccessId: UUID?,
    val customerBinding: com.karuhun.ftth.customer.CustomerFulfillmentBinding?,
    val orderBinding: com.karuhun.ftth.order.OrderFulfillmentBinding? = null,
    val bngBinding: com.karuhun.ftth.bng.BngFulfillmentBinding? = null,
    val referenceCompletion: com.karuhun.ftth.workorder.application.port.inbound.ReferenceWorkOrderCompletionView? = null,
)

data class FrozenFulfillment(val snapshot: FulfillmentApprovalSnapshot, val request: FulfillmentRequest)
