package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.WhatsAppGateway

/**
 * Port keluar untuk mengirim satu pesan ke satu nomor lewat gateway WhatsApp yang
 * SUDAH teresolusi & terdekripsi ([WhatsAppGateway]).
 *
 * Pembagian tugas: pemanggil (NotificationSender) yang memutuskan APAKAH mengirim —
 * ia menyaring saklar pemicu, meresolusi gateway, dan menjamin nomor tak kosong.
 * Dispatcher hanya tahu BAGAIMANA mengeksekusi transport sesuai tipe gateway (log,
 * HTTP generik, atau Meta Cloud), jadi menambah provider baru cukup di adapter ini.
 */
interface MessageDispatcher {

    fun send(gateway: WhatsAppGateway, phone: String, message: String): DeliveryOutcome
}

/** Hasil satu upaya kirim: status akhir plus keterangan (ref provider atau alasan gagal). */
data class DeliveryOutcome(val status: DeliveryStatus, val detail: String?)
