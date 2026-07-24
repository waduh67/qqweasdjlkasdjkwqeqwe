package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationChannel

/**
 * Port keluar untuk mengirim satu pesan ke satu penerima lewat sebuah kanal.
 *
 * Di dev hanya ada implementasi yang mencatat ke log — belum ada gateway WhatsApp/SMS
 * sungguhan di lingkungan pengembangan. Adapter nyata (mis. WA Business API) tinggal
 * dipasang belakangan tanpa menyentuh service, karena service hanya tahu port ini.
 */
interface MessageDispatcher {

    fun send(channel: NotificationChannel, phone: String?, message: String): DeliveryOutcome
}

/** Hasil satu upaya kirim: status akhir plus keterangan (ref provider atau alasan gagal/lewat). */
data class DeliveryOutcome(val status: DeliveryStatus, val detail: String?)
