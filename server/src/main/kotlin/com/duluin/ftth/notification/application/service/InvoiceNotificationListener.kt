package com.duluin.ftth.notification.application.service

import com.duluin.ftth.billing.InvoiceDueSoon
import com.duluin.ftth.billing.InvoiceOverdue
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.notification.application.service.NotificationSender.Recipient
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Mengingatkan pelanggan soal tagihannya lewat WhatsApp — pemicu `INVOICE_DUE_SOON`
 * (mendekati jatuh tempo) dan `INVOICE_OVERDUE` (sudah menunggak).
 *
 * Billing yang memutuskan KAPAN memicu (jendela pengingat + penjaga idempoten
 * `due_soon_reminded`, serta transisi OVERDUE yang hanya terjadi sekali); listener ini
 * hanya menyusun pesan & meneruskan ke [NotificationSender.dispatch], yang lalu menimbang
 * saklar pemicu tenant. Berjalan pada fase AFTER_COMMIT; tenant context dipasang dari event
 * karena sweep penagihan berjalan di luar konteks pengguna. Kegagalan cukup di-log.
 */
@Component
class InvoiceNotificationListener(
    private val sender: NotificationSender,
    private val customers: CustomerApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: InvoiceDueSoon) = notify(
        event.tenantId,
        event.customerId,
        NotificationTrigger.INVOICE_DUE_SOON,
    ) { name ->
        "Halo $name, tagihan internet Anda No. ${event.number} sebesar ${rupiah(event.amount)} akan " +
            "jatuh tempo pada ${date(event.dueDate)}. Mohon lakukan pembayaran sebelum jatuh tempo. Terima kasih." +
            payLink(event.payUrl)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: InvoiceOverdue) = notify(
        event.tenantId,
        event.customerId,
        NotificationTrigger.INVOICE_OVERDUE,
    ) { name ->
        "Halo $name, tagihan internet Anda No. ${event.number} sebesar ${rupiah(event.amount)} telah " +
            "melewati jatuh tempo (${date(event.dueDate)}) dan belum kami terima. Mohon segera lakukan " +
            "pembayaran agar layanan tidak terganggu. Terima kasih." +
            payLink(event.payUrl)
    }

    private fun notify(
        tenantId: UUID,
        customerId: UUID,
        trigger: NotificationTrigger,
        message: (name: String) -> String,
    ) {
        try {
            TenantContext.runAs(tenantId) {
                val customer = customers.findCustomer(customerId) ?: return@runAs
                sender.dispatch(
                    trigger = trigger,
                    message = message(customer.name),
                    recipients = listOf(Recipient(customer.id, customer.name, customer.phone)),
                )
            }
        } catch (ex: Exception) {
            log.warn("Notifikasi {} gagal untuk tenant {} pelanggan {}", trigger, tenantId, customerId, ex)
        }
    }

    /**
     * Ekor pesan berisi tautan halaman bayar publik tagihan itu — biar pelanggan bisa membayar
     * langsung dari WhatsApp tanpa login portal. Kosong bila billing tak bisa merangkainya (URL
     * basis web belum disetel): pesan lama terkirim apa adanya.
     */
    private fun payLink(payUrl: String?): String = payUrl?.let { "\n\nBayar di sini: $it" } ?: ""

    private fun rupiah(amount: BigDecimal): String = "Rp " + RUPIAH.format(amount)

    private fun date(date: LocalDate): String = DATE.format(date)

    private companion object {
        val ID_LOCALE: Locale = Locale.forLanguageTag("id")
        val RUPIAH = DecimalFormat("#,##0", DecimalFormatSymbols(ID_LOCALE))
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", ID_LOCALE)
    }
}
