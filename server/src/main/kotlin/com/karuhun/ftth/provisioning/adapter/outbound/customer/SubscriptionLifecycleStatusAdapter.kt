package com.karuhun.ftth.provisioning.adapter.outbound.customer

import com.karuhun.ftth.customer.CustomerApi
import com.karuhun.ftth.provisioning.application.port.outbound.SubscriptionLifecycleStatusPort
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SubscriptionLifecycleStatusAdapter(
    private val customers: CustomerApi,
) : SubscriptionLifecycleStatusPort {
    override fun statusOf(subscriptionId: UUID): String? = customers.findSubscription(subscriptionId)?.status
}
