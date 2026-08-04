package com.duluin.ftth.platformbilling.application.port.inbound

import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.LocalDate

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
}

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
