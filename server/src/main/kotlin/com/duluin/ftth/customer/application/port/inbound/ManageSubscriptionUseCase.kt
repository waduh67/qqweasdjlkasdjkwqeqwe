package com.duluin.ftth.customer.application.port.inbound

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface ManageSubscriptionUseCase {

    fun listForCustomer(customerId: UUID): List<SubscriptionView>

    fun create(customerId: UUID, command: SaveSubscriptionCommand): SubscriptionView

    fun update(id: UUID, command: SaveSubscriptionCommand): SubscriptionView

    fun activate(id: UUID): SubscriptionView

    /**
     * Aktivasi langganan hasil impor/backfill: pelanggan sudah terpasang di lapangan, jadi
     * [activatedAt] menjadi basis prorata (null = sekarang) dan [billingDayOfMonth] menyetel
     * langsung tanggal tagih dari kolom CSV (null = ikut snapshot paket). Memancarkan
     * SubscriptionActivated sama seperti [activate] sehingga sinkron akses & billing ikut jalan.
     */
    fun activateImported(id: UUID, activatedAt: Instant?, billingDayOfMonth: Int?): SubscriptionView

    /**
     * Setel ulang HANYA tanggal tagih sebuah langganan (jalur upsert impor CSV memperbarui
     * `next_billing` tanpa mengganti paket). null = kembalikan ke kebijakan billing global.
     * Tak memancarkan event — tanggal tagih tak menyentuh sisi jaringan/RADIUS.
     */
    fun overrideBilling(id: UUID, billingDayOfMonth: Int?): SubscriptionView

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
