package com.duluin.ftth.customer.application.port.inbound

import java.math.BigDecimal
import java.util.UUID

interface ManageSubscriptionUseCase {

    fun listForCustomer(customerId: UUID): List<SubscriptionView>

    fun create(customerId: UUID, command: SaveSubscriptionCommand): SubscriptionView

    fun update(id: UUID, command: SaveSubscriptionCommand): SubscriptionView

    fun activate(id: UUID): SubscriptionView

    /** Isolir sementara, mis. karena tunggakan — perangkat tetap terpasang. */
    fun isolate(id: UUID): SubscriptionView

    fun terminate(id: UUID): SubscriptionView
}

data class SaveSubscriptionCommand(
    val packageName: String,
    val bandwidthMbps: Int,
    val monthlyFee: BigDecimal,
)
