package com.duluin.ftth.customer.application.port.outbound

import com.duluin.ftth.customer.domain.model.Subscription
import java.util.UUID

interface SubscriptionRepository {

    fun save(subscription: Subscription): Subscription

    fun findById(id: UUID): Subscription?

    fun findByCustomerId(customerId: UUID): List<Subscription>

    fun findByCustomerIds(customerIds: Set<UUID>): List<Subscription>

    /** Langganan ACTIVE/ISOLATED tenant aktif — kandidat penagihan periode berjalan. */
    fun findBillableForCurrentTenant(): List<Subscription>

    fun deleteById(id: UUID)
}
