package com.duluin.ftth.billing

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Kontrak publik modul `billing` untuk modul lain (mis. aggregator Subscriber-360).
 * Menyediakan RINGKASAN rekening satu pelanggan — saldo tunggakan dihitung di SERVER
 * (dulu di browser: `web/.../billing.ts` + TagihanTab), jadi angka uang punya satu
 * sumber kebenaran. Billing "sink": tak pernah memanggil balik pemanggilnya.
 */
interface BillingApi {

    /**
     * Ringkasan rekening pelanggan: tunggakan, jumlah tagihan belum lunas, jatuh tempo
     * terlama, dan pembayaran terakhir. Selalu mengembalikan objek (nol tagihan =
     * ringkasan bernilai nol), bukan null.
     */
    fun findAccountSummary(customerId: UUID): BillingAccountSummary
}

/**
 * Ringkasan rekening satu pelanggan pada saat dibaca. [outstandingAmount] = Σ nilai
 * tagihan MENUNGGAK (berstatus OVERDUE atau ISSUED yang sudah lewat jatuh tempo);
 * PAID/VOID dikecualikan. [outstandingCount] jumlah tagihan menunggak itu;
 * [unpaidCount] jumlah tagihan belum lunas apa pun (ISSUED+OVERDUE, termasuk yang belum
 * jatuh tempo). [oldestDueDate] = jatuh tempo paling lama di antara yang menunggak
 * (indikator berapa lama nunggak). [lastPaidAt] = pembayaran terakhir (paidAt terbaru
 * lintas tagihan).
 */
data class BillingAccountSummary(
    val customerId: UUID,
    val outstandingAmount: BigDecimal,
    val outstandingCount: Int,
    val unpaidCount: Int,
    val oldestDueDate: LocalDate?,
    val lastPaidAt: Instant?,
)
