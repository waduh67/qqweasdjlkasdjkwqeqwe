package com.duluin.ftth.platformbilling.application.port.inbound

import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Sisi TENANT (bukan super-admin): tenant admin melihat langganan aplikasinya sendiri —
 * masa aktif, status, riwayat tagihan, pemakaian (kosmetik) — dan memperpanjang mandiri
 * lewat gateway aktif. Selalu untuk tenant pada konteks berjalan; dijaga izin
 * `billing.subscription.*`.
 */
interface TenantSelfSubscriptionUseCase {
    /** Langganan tenant konteks berjalan; null bila tenant belum berlangganan. */
    fun current(): TenantSelfSubscriptionView?

    /**
     * Terbitkan (atau ambil kembali) tagihan untuk dibayar via gateway aktif, lalu kembalikan
     * tagihan berisi tautan bayar. [months] = jumlah bulan dibayar di muka (1..12); nilai tagihan
     * `biaya × months`. Masa aktif baru bertambah sebanyak [months] saat tagihan LUNAS.
     */
    fun renew(months: Int = 1): SubscriptionInvoiceView

    /**
     * Buat charge in-app (VA/QRIS) untuk satu tagihan tertunggak milik tenant berjalan dengan
     * instrumen [method] (`VIRTUAL_ACCOUNT`/`QR`) + [channel] bank (wajib untuk VA), lalu kembalikan
     * tagihan berisi instruksi bayar. NotFound bila tagihan bukan milik tenant ini; Validation bila
     * tagihan sudah lunas/void (tak dapat dibayar) atau metode/channel tak didukung.
     */
    fun payInvoice(invoiceId: UUID, method: String, channel: String?): SubscriptionInvoiceView

    /**
     * **Alat uji (sandbox saja)**: paksa sesi bayar tagihan langganan milik tenant berjalan menjadi
     * [status] (`SUCCESS`/`EXPIRED`) lewat simulasi penyedia, memakai id sesi dari charge terakhir.
     * Pelunasan (dan perpanjangan masa aktif) tetap datang lewat webhook penyedia, jadi proyeksi
     * yang dikembalikan BELUM tentu berubah status — klien memuat ulang/polling.
     */
    fun simulateInvoicePayment(invoiceId: UUID, status: SimulatedChargeStatus): SubscriptionInvoiceView

    /**
     * Keadaan kunci baca-saja tenant berjalan. Sengaja terpisah dari [current] dan TANPA izin
     * `billing.*`: teknisi dan CS yang tak punya izin langganan pun perlu tahu kenapa tombol
     * simpannya mendadak mati — layar yang membeku tanpa penjelasan menghasilkan tiket dukungan,
     * bukan pembayaran.
     */
    fun lockState(): SubscriptionLockView
}

/**
 * Ringkasan kunci baca-saja untuk klien: cukup untuk merangkai banner dan halaman langganan
 * tanpa memanggil endpoint lain.
 *
 * @param daysOverdue umur tunggakan TERTUA dalam hari; 0 bila tak ada yang lewat jatuh tempo.
 * @param invoiceId tagihan tertunggak tertua — yang harus dibayar lebih dulu; null bila tak ada.
 */
data class SubscriptionLockView(
    val locked: Boolean,
    val daysOverdue: Long,
    val dueDate: LocalDate?,
    val amountDue: BigDecimal,
    val currency: String,
    val invoiceId: UUID?,
)

/** Pandangan langganan sisi tenant + pemakaian kosmetik. */
data class TenantSelfSubscriptionView(
    val status: SubscriptionStatus,
    val monthlyFee: BigDecimal,
    /** Masa aktif (currentPeriodEnd); null bila belum pernah aktif (belum ada pembayaran). */
    val activeUntil: LocalDate?,
    val currentPeriodStart: LocalDate?,
    val nextInvoiceAt: LocalDate?,
    val usage: List<UsageMetricView>,
    val invoices: List<SubscriptionInvoiceView>,
)

/** Satu baris pemakaian kosmetik — [limit] null artinya "Unlimited". */
data class UsageMetricView(
    val key: String,
    val label: String,
    val used: Long,
    val limit: Long?,
)
