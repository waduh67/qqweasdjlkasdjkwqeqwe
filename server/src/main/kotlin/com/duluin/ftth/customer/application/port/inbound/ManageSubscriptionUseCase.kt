package com.duluin.ftth.customer.application.port.inbound

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Langganan seorang pelanggan — TUNGGAL. Tak ada operasi "tambah langganan" di kontrak ini:
 * satu pelanggan memegang satu langganan seumur hidupnya (V107), yang statusnya berpindah dan
 * paketnya bisa diganti di tempat.
 */
interface ManageSubscriptionUseCase {

    /** null bila pelanggan warisan yang belum pernah dipasangi paket. */
    fun findForCustomer(customerId: UUID): SubscriptionView?

    /**
     * Menetapkan paket pelanggan — SATU pintu untuk tiga kejadian yang di mata operator memang
     * satu gerakan ("paket orang ini sekarang X"):
     *  1. belum punya langganan (pelanggan warisan) → langganannya dibuka;
     *  2. langganannya sudah berakhir → baris yang sama dihidupkan lagi, bukan ditambah baris
     *     kedua (username PPPoE & riwayat tagihannya ikut lestari — lihat `Subscription.resubscribe`);
     *  3. langganannya berjalan → paketnya diganti di tempat, dan bila paketnya benar-benar
     *     berpindah, sisi jaringan diberi tahu supaya kecepatannya ikut pindah.
     *
     * Idempoten: memanggil ulang dengan paket yang sama tak mengantre pekerjaan RADIUS apa pun.
     */
    fun setPlan(customerId: UUID, command: SaveSubscriptionCommand): SubscriptionView

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
 * Menetapkan paket dengan MERUJUK katalog (bukan teks bebas). Sisi komersial paket
 * di-snapshot ke langganan; [monthlyFeeOverride] mengizinkan harga negosiasi
 * per-pelanggan (null = pakai harga paket).
 */
data class SaveSubscriptionCommand(
    val planId: UUID,
    val monthlyFeeOverride: BigDecimal? = null,
)
