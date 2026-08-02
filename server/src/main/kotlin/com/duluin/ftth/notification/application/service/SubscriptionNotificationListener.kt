package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.SubscriptionActivated
import com.duluin.ftth.customer.SubscriptionIsolated
import com.duluin.ftth.customer.SubscriptionTerminated
import com.duluin.ftth.notification.application.service.NotificationSender.Recipient
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Memberi tahu pelanggan lewat WhatsApp saat daur hidup langganannya berubah
 * (aktif / isolir / berhenti) — pemicu `SUBSCRIPTION_*`.
 *
 * Berjalan pada fase AFTER_COMMIT: pesan hanya dikirim untuk perubahan langganan yang
 * benar-benar ter-commit. Tenant context dipasang dari event karena penerbitnya (module
 * customer / billing / workorder) bisa berjalan di luar konteks pengguna. `fallbackExecution`
 * agar event tanpa transaksi tetap diproses. [NotificationSender.dispatch] sendiri yang
 * memutuskan kirim/tidak lewat saklar pemicu tenant — di sini kegagalan cukup di-log agar
 * tak menggagalkan operasi langganan yang menerbitkannya. Pelanggan tanpa nomor telepon
 * tetap tercatat SKIPPED oleh sender.
 */
@Component
class SubscriptionNotificationListener(
    private val sender: NotificationSender,
    private val customers: CustomerApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionActivated) = notify(
        event.tenantId,
        event.customerId,
        NotificationTrigger.SUBSCRIPTION_ACTIVATED,
    ) { "Halo $it, layanan internet Anda sudah AKTIF. Selamat menikmati koneksi kami 🙏" }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionIsolated) = notify(
        event.tenantId,
        event.customerId,
        NotificationTrigger.SUBSCRIPTION_ISOLATED,
    ) {
        "Halo $it, layanan internet Anda untuk sementara kami NONAKTIFKAN. " +
            "Mohon selesaikan kewajiban tagihan Anda agar layanan dapat kami pulihkan kembali."
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionTerminated) = notify(
        event.tenantId,
        event.customerId,
        NotificationTrigger.SUBSCRIPTION_TERMINATED,
    ) { "Halo $it, layanan internet Anda telah DIHENTIKAN. Terima kasih telah menjadi pelanggan kami." }

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
}
