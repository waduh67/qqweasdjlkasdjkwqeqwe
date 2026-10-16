package com.karuhun.ftth.provisioning

import com.karuhun.ftth.fulfillment.FulfillmentCoordinator
import com.karuhun.ftth.fulfillment.FulfillmentOutcome
import com.karuhun.ftth.fulfillment.FulfillmentRequest
import org.springframework.stereotype.Component

interface FulfillmentApi {
    fun accept(request: FulfillmentRequest): FulfillmentOutcome
}

@Component
class ProvisioningFulfillmentApi(private val coordinator: FulfillmentCoordinator) : FulfillmentApi {
    override fun accept(request: FulfillmentRequest): FulfillmentOutcome = coordinator.accept(request)
}
