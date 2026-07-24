package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.notification.domain.model.NotificationChannel
import java.util.UUID

/** Menyiarkan pesan ke pelanggan. Untuk saat ini selalu berangkat dari sebuah insiden. */
interface SendBroadcastUseCase {

    fun broadcastForIncident(command: SendIncidentBroadcastCommand): BroadcastView
}

/**
 * Perintah menyiarkan pemberitahuan gangguan ke seluruh pelanggan terdampak sebuah
 * insiden. Dipicu manual oleh operator — pesannya ia susun sendiri, kanalnya ia pilih
 * (default WhatsApp). Pengiriman otomatis saat insiden terbuka sengaja belum dipasang.
 */
data class SendIncidentBroadcastCommand(
    val incidentId: UUID,
    val channel: NotificationChannel,
    val message: String,
)
