package com.duluin.ftth.provisioning

import com.duluin.ftth.fulfillment.FulfillmentCoordinator
import com.duluin.ftth.fulfillment.FulfillmentOutcome
import com.duluin.ftth.fulfillment.FulfillmentRequest
import org.springframework.stereotype.Component

interface FulfillmentApi {
    fun accept(request: FulfillmentRequest): FulfillmentOutcome
}

@Component
class ProvisioningFulfillmentApi(private val coordinator: FulfillmentCoordinator) : FulfillmentApi {
    override fun accept(request: FulfillmentRequest): FulfillmentOutcome = coordinator.accept(request)
}
