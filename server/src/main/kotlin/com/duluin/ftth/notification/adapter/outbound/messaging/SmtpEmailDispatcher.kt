package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.application.port.outbound.OutboundEmail
import com.duluin.ftth.notification.config.MailProperties
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/**
 * Pengirim email lewat SMTP platform.
 *
 * Sender diambil dari [SmtpSenderFactory] karena boleh saja tak ada: baik baris setelan di
 * DB maupun `spring.mail.*` bisa kosong. Tanpa keduanya — keadaan bawaan pengembangan dan
 * deploy yang belum menyiapkan SMTP — dispatcher jatuh ke mode catat-ke-log dan tetap
 * melaporkan [DeliveryStatus.SENT], persis seperti gateway `WhatsAppProvider.LOG`. Konsisten
 * begitu supaya alur pemulihan password bisa dicoba utuh di lab: kodenya muncul di log.
 *
 * Suratnya multipart: HTML berlogo plus teks polos. Yang dicatat ke log dalam mode uji
 * adalah versi TEKSNYA — sebuah dump HTML seratus baris di log hanya menyembunyikan kode
 * pemulihan yang justru sedang dicari orang yang membacanya.
 */
@Component
class SmtpEmailDispatcher(
    private val senders: SmtpSenderFactory,
    private val properties: MailProperties,
) : EmailDispatcher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: OutboundEmail): DeliveryOutcome {
        val from = message.fromAddress?.trim()?.takeIf { it.isNotEmpty() } ?: properties.from.trim()
        val sender = senders.current()
        if (sender == null || from.isEmpty()) {
            log.info("[MAIL/LOG] → {} | {} | {}", message.to, message.subject, message.textBody)
            return DeliveryOutcome(DeliveryStatus.SENT, "dicatat ke log (SMTP belum disetel)")
        }
        val displayName = message.fromName?.trim()?.takeIf { it.isNotEmpty() } ?: properties.fromName.trim()
        return runCatching {
            val mime = sender.createMimeMessage()
            // multipart = true → JavaMail merangkai `multipart/alternative`; klien memilih
            // bagian terkaya yang bisa direndernya sendiri.
            val helper = MimeMessageHelper(mime, true, Charsets.UTF_8.name())
            if (displayName.isEmpty()) helper.setFrom(from) else helper.setFrom(from, displayName)
            helper.setTo(message.to)
            helper.setSubject(message.subject)
            // Urutan argumen penting: teks dulu, HTML kemudian — itu urutan yang menaruh
            // bagian terkaya paling belakang, sesuai yang diharapkan klien email.
            helper.setText(message.textBody, message.htmlBody)
            message.replyTo?.trim()?.takeIf { it.isNotEmpty() }?.let { helper.setReplyTo(it) }
            sender.send(mime)
        }.fold(
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
