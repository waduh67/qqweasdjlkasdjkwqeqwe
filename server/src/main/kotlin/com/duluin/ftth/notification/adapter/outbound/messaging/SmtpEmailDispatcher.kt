package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.config.MailProperties
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/**
 * Pengirim email lewat SMTP platform.
 *
 * [JavaMailSender] diambil lewat [ObjectProvider] karena bean-nya BOLEH tak ada: Spring Boot
 * hanya membuatnya bila `spring.mail.host` diisi. Tanpa itu — keadaan bawaan pengembangan dan
 * deploy yang belum menyiapkan SMTP — dispatcher jatuh ke mode catat-ke-log dan tetap
 * melaporkan [DeliveryStatus.SENT], persis seperti gateway `WhatsAppProvider.LOG`. Konsisten
 * begitu supaya alur pemulihan password bisa dicoba utuh di lab: kodenya muncul di log.
 *
 * Isinya teks polos, bukan HTML. Pesan transaksional (kode pemulihan) justru lebih mungkin
 * lolos filter spam dan terbaca di semua klien dalam bentuk sesederhana ini.
 */
@Component
class SmtpEmailDispatcher(
    private val senderProvider: ObjectProvider<JavaMailSender>,
    private val properties: MailProperties,
) : EmailDispatcher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, subject: String, body: String, fromName: String?): DeliveryOutcome {
        val from = properties.from.trim()
        val sender = senderProvider.getIfAvailable()
        if (sender == null || from.isEmpty()) {
            log.info("[MAIL/LOG] → {} | {} | {}", to, subject, body)
            return DeliveryOutcome(DeliveryStatus.SENT, "dicatat ke log (SMTP belum disetel)")
        }
        // Nama ISP menang atas nama platform bila pemanggil menyebutnya.
        val displayName = fromName?.trim()?.takeIf { it.isNotEmpty() } ?: properties.fromName.trim()
        val message = SimpleMailMessage().apply {
            // Nama tampilan digabung ke header From; alamat wajib berada dalam kurung sudut.
            setFrom(displayName.takeIf { it.isNotEmpty() }?.let { "$it <$from>" } ?: from)
            setTo(to)
            setSubject(subject)
            setText(body)
        }
        return runCatching { sender.send(message) }.fold(
            onSuccess = { DeliveryOutcome(DeliveryStatus.SENT, "Terkirim via SMTP") },
            onFailure = { DeliveryOutcome(DeliveryStatus.FAILED, transportError(it)) },
        )
    }

    /** Rapikan error transport jadi keterangan ringkas (muat kolom detail 300 char). */
    private fun transportError(e: Throwable): String {
        val cause = e.message?.take(MAX_DETAIL) ?: e.javaClass.simpleName
        return "SMTP gagal: $cause"
    }

    private companion object {
        const val MAX_DETAIL = 200
    }
}
