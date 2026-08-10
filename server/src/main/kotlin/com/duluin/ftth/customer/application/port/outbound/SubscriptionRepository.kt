package com.duluin.ftth.customer.application.port.outbound

import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface SubscriptionRepository {

    fun save(subscription: Subscription): Subscription

    fun findById(id: UUID): Subscription?

    fun findByCustomerId(customerId: UUID): List<Subscription>

    fun findByCustomerIds(customerIds: Set<UUID>): List<Subscription>

    /** Resolusi sekumpulan langganan sekaligus (ekspor CSV); id yang tak ada diabaikan. */
    fun findByIds(ids: Set<UUID>): List<Subscription>

    /** Langganan ACTIVE/ISOLATED tenant aktif — kandidat penagihan periode berjalan. */
    fun findBillableForCurrentTenant(): List<Subscription>

    /** Cacah langganan tenant aktif per status — untuk laporan. */
    fun countByStatus(): Map<SubscriptionStatus, Long>

    /**
     * Jumlah tarif bulanan langganan penghasil MRR (ACTIVE+ISOLATED) tenant aktif —
     * terisolir tetap ditagih, jadi tetap dihitung sebagai pendapatan berulang.
     */
    fun sumMonthlyRecurringRevenue(): BigDecimal

    /** Langganan yang MULAI hidup (`activatedAt`) di [from]..[toExclusive) — pertumbuhan periode. */
    fun countActivatedBetween(from: Instant, toExclusive: Instant): Long

    /** Langganan yang BERHENTI (`terminatedAt`) di [from]..[toExclusive) — churn periode. */
    fun countTerminatedBetween(from: Instant, toExclusive: Instant): Long

    /**
     * Berapa langganan yang HIDUP pada [at]: sudah teraktivasi sebelum/pada saat itu dan belum
     * diakhiri sesudahnya. Basis (penyebut) laju churn — dihitung dari tanggal aktivasi/terminasi
     * yang tersimpan, bukan dari riwayat status, karena satu langganan hanya sekali hidup-mati.
     */
    fun countLiveAt(at: Instant): Long

    fun deleteById(id: UUID)
}
