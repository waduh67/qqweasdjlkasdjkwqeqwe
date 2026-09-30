package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.security.SessionIdentity
import com.duluin.ftth.inventory.MaterialSettlementSource
import com.duluin.ftth.workorder.ApprovedWorkOrderContext
import java.util.UUID

data class FulfillmentApprovalSnapshot(
    val id: UUID,
    val identity: SessionIdentity,
    val cutoverEpoch: Long,
    val workOrder: ApprovedWorkOrderContext,
    val material: MaterialSettlementSource,
    val effects: Set<FulfillmentEffectType>,
    val orderRevision: Long?,
    val visit: com.duluin.ftth.fieldservice.VisitRef?,
    val subscription: com.duluin.ftth.customer.SubscriptionRef?,
    val bngAccessId: UUID?,
    val customerBinding: com.duluin.ftth.customer.CustomerFulfillmentBinding?,
    val orderBinding: com.duluin.ftth.order.OrderFulfillmentBinding? = null,
    val bngBinding: com.duluin.ftth.bng.BngFulfillmentBinding? = null,
)

data class FrozenFulfillment(val snapshot: FulfillmentApprovalSnapshot, val request: FulfillmentRequest)
