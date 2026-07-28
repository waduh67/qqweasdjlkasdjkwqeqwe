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

/**
 * Buat/ubah langganan dengan MERUJUK paket katalog (bukan lagi teks bebas). Sisi
 * komersial paket di-snapshot ke langganan; [monthlyFeeOverride] mengizinkan harga
 * negosiasi per-pelanggan (null = pakai harga paket).
 */
data class SaveSubscriptionCommand(
    val planId: UUID,
    val monthlyFeeOverride: BigDecimal? = null,
)
