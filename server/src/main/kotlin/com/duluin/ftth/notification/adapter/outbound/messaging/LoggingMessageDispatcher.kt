package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Dispatcher pengembangan: hanya mencatat pesan ke log, tidak mengirim apa pun.
 *
 * Lingkungan dev tidak punya gateway WhatsApp/SMS sungguhan, dan justru bagus
 * begitu — broadcast bisa diuji tanpa risiko benar-benar mengganggu pelanggan.
 * Adapter produksi (mis. WA Business API) mengimplementasikan [MessageDispatcher]
 * yang sama dan menggantikan bean ini lewat profil/konfigurasi, tanpa menyentuh
 * service.
 */
@Component
class LoggingMessageDispatcher : MessageDispatcher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(channel: NotificationChannel, phone: String?, message: String): DeliveryOutcome {
        if (phone.isNullOrBlank()) {
            // Tanpa nomor tak ada yang bisa dikirimi — dilewati, bukan gagal.
            return DeliveryOutcome(DeliveryStatus.SKIPPED, "Nomor telepon kosong")
        }
        log.info("[BROADCAST/{}] → {} : {}", channel, phone, message)
        return DeliveryOutcome(DeliveryStatus.SENT, "dicatat ke log (dev)")
    }
}
