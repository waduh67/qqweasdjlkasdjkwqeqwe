package com.duluin.ftth.order

import java.util.UUID

data class OrderFulfillmentTarget(val orderId: UUID, val customerId: UUID, val expectedRevision: Long? = null)
data class OrderFulfillmentBinding(val tenantId: UUID, val orderId: UUID, val customerId: UUID, val revision: Long, val state: String)
