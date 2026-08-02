package com.duluin.ftth.billing

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Peristiwa penagihan yang diterbitkan module billing sebagai titik kait notifikasi.
 *
 * Diletakkan di base package (permukaan publik billing) supaya module `notification`
 * bisa mengingatkan pelanggan tanpa bergantung pada internal billing dan tanpa
 * ketergantungan balik. Billing yang memutuskan KAPAN (jendela pengingat + penjaga
 * idempoten `due_soon_reminded`); notification yang memutuskan APAKAH mengirim (saklar
 * pemicu tenant). Penerbitan in-JVM; konsumen mendengarkan pada fase AFTER_COMMIT agar
 * hanya melihat tagihan yang benar-benar ter-commit.
 */

/** Tagihan mendekati jatuh tempo — pelanggan diingatkan sekali (pemicu INVOICE_DUE_SOON). */
data class InvoiceDueSoon(
    val tenantId: UUID,
    val invoiceId: UUID,
    val customerId: UUID,
    val number: String,
    val amount: BigDecimal,
    val dueDate: LocalDate,
)

/** Tagihan lewat jatuh tempo (ditandai menunggak) — pelanggan diberi tahu (pemicu INVOICE_OVERDUE). */
data class InvoiceOverdue(
    val tenantId: UUID,
    val invoiceId: UUID,
    val customerId: UUID,
    val number: String,
    val amount: BigDecimal,
    val dueDate: LocalDate,
)
