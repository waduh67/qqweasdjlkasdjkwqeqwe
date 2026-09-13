package com.duluin.ftth.bng

import com.duluin.ftth.common.domain.FulfillmentEffectReference
import com.duluin.ftth.common.security.AuthorityFence
import java.util.UUID

interface BngFulfillmentApi {
    fun lock(subscriptionId: UUID, customerId: UUID): BngFulfillmentBinding?
    fun apply(command: BngFulfillmentCommand, authority: AuthorityFence)
}

data class BngFulfillmentBinding(val tenantId: UUID, val accessId: UUID, val subscriptionId: UUID, val customerId: UUID,
    val revision: String, val state: String, val nasId: UUID?)
data class BngFulfillmentCommand(val reference: FulfillmentEffectReference, val binding: BngFulfillmentBinding, val action: BngFulfillmentAction)
