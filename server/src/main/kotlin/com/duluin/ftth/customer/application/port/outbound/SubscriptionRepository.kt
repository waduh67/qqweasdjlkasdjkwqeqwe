package com.duluin.ftth.customer.application.port.outbound

import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.util.UUID

interface SubscriptionRepository {

    fun save(subscription: Subscription): Subscription

    fun findById(id: UUID): Subscription?

    fun findByCustomerId(customerId: UUID): List<Subscription>

    fun findByCustomerIds(customerIds: Set<UUID>): List<Subscription>

    /** Langganan ACTIVE/ISOLATED tenant aktif — kandidat penagihan periode berjalan. */
    fun findBillableForCurrentTenant(): List<Subscription>

    /** Cacah langganan tenant aktif per status — untuk laporan. */
    fun countByStatus(): Map<SubscriptionStatus, Long>

    /**
     * Jumlah tarif bulanan langganan penghasil MRR (ACTIVE+ISOLATED) tenant aktif —
     * terisolir tetap ditagih, jadi tetap dihitung sebagai pendapatan berulang.
     */
    fun sumMonthlyRecurringRevenue(): BigDecimal

    fun deleteById(id: UUID)
}
