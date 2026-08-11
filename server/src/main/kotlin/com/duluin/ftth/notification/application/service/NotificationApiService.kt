package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.NotificationApi
import com.duluin.ftth.notification.TransactionalChannel
import com.duluin.ftth.notification.TransactionalDelivery
import com.duluin.ftth.notification.TransactionalMessage
import com.duluin.ftth.notification.TransactionalPurpose
import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Implementasi [NotificationApi]: mengirim pesan transaksional TANPA menyentuh riwayat
 * broadcast (alasannya di KDoc [NotificationApi]).
 *
 * Selain itu ia sengaja MELEWATI saklar pemicu tenant. Pesan di sini bukan pemberitahuan
 * yang boleh dimatikan sebagai selera — pemulihan password adalah bagian dari mekanisme
 * masuk, dan ISP yang mematikan notifikasi langganan tak boleh diam-diam ikut memutus
 * satu-satunya jalan pelanggannya kembali ke akunnya. Saklar induk gateway tetap dihormati:
 * kalau gateway WA memang mati, pesannya tak bisa keluar dan itu dilaporkan apa adanya.
 */
@Service
class NotificationApiService(
    private val settingsRepo: NotificationSettingsRepository,
    private val templates: WhatsAppTemplateResolver,
    private val dispatcher: MessageDispatcher,
    private val emailDispatcher: EmailDispatcher,
    private val branding: EmailBrandingResolver,
    private val renderer: EmailRenderer,
) : NotificationApi {

    @Transactional(readOnly = true)
    override fun sendTransactional(message: TransactionalMessage): TransactionalDelivery {
        val outcome = when (message.channel) {
            TransactionalChannel.EMAIL -> sendEmail(message)
            TransactionalChannel.WHATSAPP -> sendWhatsApp(message)
        }
        return TransactionalDelivery(delivered = outcome.status == DeliveryStatus.SENT, detail = outcome.detail)
    }

    /**
     * Subjeknya datang dari pemanggil (module portal), bukan dari [EmailSubjectResolver]:
     * pesan di sini tak lahir dari pemicu yang punya baris subjek sendiri. Yang tetap
     * dipinjam adalah BUNGKUSNYA, supaya email pemulihan password tak tampak asing di
     * kotak masuk dibanding pemberitahuan lain dari ISP yang sama.
     */
    private fun sendEmail(message: TransactionalMessage) = emailDispatcher.send(
        renderer.render(
            to = message.destination,
            subject = message.subject,
            body = message.body,
            identity = branding.forCurrentTenant(),
        ),
    )

    private fun sendWhatsApp(message: TransactionalMessage): DeliveryOutcome {
        val settings = settingsRepo.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())
        val gateway = settings.resolveGateway()
            ?: return DeliveryOutcome(DeliveryStatus.SKIPPED, "Gateway WA nonaktif")
        val resolved = templates.withTemplateFor(gateway, message.purpose.toTrigger())
        return dispatcher.send(resolved, message.destination, message.recipientName, message.body)
    }

    /** Jembatan tujuan publik → pemicu internal, satu-satunya tempat keduanya bertemu. */
    private fun TransactionalPurpose.toTrigger(): NotificationTrigger = when (this) {
        TransactionalPurpose.PORTAL_PASSWORD_RESET -> NotificationTrigger.PORTAL_PASSWORD_RESET
    }
}
