package com.karuhun.ftth.customer

import com.karuhun.ftth.common.domain.FulfillmentEffectReference
import com.karuhun.ftth.common.security.AuthorityFence
import java.util.UUID

interface CustomerFulfillmentApi {
    fun apply(command: CustomerFulfillmentCommand, authority: AuthorityFence)
}

enum class CustomerFulfillmentAction { ACTIVATE, TERMINATE }
data class CustomerFulfillmentCommand(val reference: FulfillmentEffectReference, val customerId: UUID,
    val subscriptionId: UUID, val action: CustomerFulfillmentAction)
