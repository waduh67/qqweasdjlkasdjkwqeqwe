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
 * HTTP generik, Meta Cloud, atau Mekari Qontak), jadi menambah provider baru cukup
 * di adapter ini.
 */
interface MessageDispatcher {

    /**
     * @param recipientName nama penerima. Hanya Qontak yang memakainya (`to_name` wajib di
     *   API broadcast-nya); transport lain mengabaikannya. Dibawa sebagai parameter alih-alih
     *   ditebak dari nomor karena pemanggil memang sudah memegangnya.
     */
    fun send(gateway: WhatsAppGateway, phone: String, recipientName: String, message: String): DeliveryOutcome
}

/** Hasil satu upaya kirim: status akhir plus keterangan (ref provider atau alasan gagal). */
data class DeliveryOutcome(val status: DeliveryStatus, val detail: String?)
