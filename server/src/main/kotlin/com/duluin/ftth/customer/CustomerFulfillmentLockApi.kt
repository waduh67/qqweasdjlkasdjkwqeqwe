package com.duluin.ftth.customer

import java.util.UUID

interface CustomerFulfillmentLockApi {
    fun lock(customerId: UUID, subscriptionId: UUID?): CustomerFulfillmentBinding
}

data class CustomerFulfillmentBinding(val customerId: UUID, val customerRevision: String,
    val subscriptionId: UUID?, val subscriptionRevision: String?)
