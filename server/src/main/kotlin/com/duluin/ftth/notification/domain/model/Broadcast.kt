package com.duluin.ftth.notification.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/** Kanal pengiriman pesan ke pelanggan. */
enum class NotificationChannel { WHATSAPP, SMS, TELEGRAM }

/**
 * Hasil pengiriman ke satu penerima.
 *
 * `SKIPPED` (mis. nomor telepon kosong) sengaja dibedakan dari `FAILED` (kanal
 * menolak/error): yang pertama tak bisa diapa-apakan, yang kedua layak dicoba ulang.
 */
enum class DeliveryStatus { SENT, SKIPPED, FAILED }

/** Satu penerima broadcast beserta hasil pengirimannya — snapshot pada titik waktu kirim. */
class BroadcastRecipient private constructor(
    val id: UUID,
    val tenantId: UUID,
    val broadcastId: UUID,
    val customerId: UUID?,
    val customerName: String,
    val phone: String?,
    val status: DeliveryStatus,
    val detail: String?,
    val at: Instant,
) {
    companion object {
        @Suppress("LongParameterList")
        fun of(
            tenantId: UUID,
            broadcastId: UUID,
            customerId: UUID?,
            customerName: String,
            phone: String?,
            status: DeliveryStatus,
            detail: String?,
            at: Instant,
        ) = BroadcastRecipient(UuidV7.generate(), tenantId, broadcastId, customerId, customerName, phone, status, detail, at)

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            broadcastId: UUID,
            customerId: UUID?,
            customerName: String,
            phone: String?,
            status: DeliveryStatus,
            detail: String?,
            at: Instant,
        ) = BroadcastRecipient(id, tenantId, broadcastId, customerId, customerName, phone, status, detail, at)
    }
}

/**
 * Broadcast: satu penyiaran pesan ke sekumpulan pelanggan, biasanya pemberitahuan
 * gangguan proaktif ("layanan Anda sedang terganggu, tim kami menanganinya") sebelum
 * mereka komplain.
 *
 * Bersifat catatan titik-waktu (append-only): begitu tersiar, penerima dan hasilnya
 * tidak berubah lagi. Agregat disusun ([compose]) lalu tiap hasil pengiriman dicatat
 * ([record]) sebelum dipersistensi bersama penerimanya.
 */
class Broadcast private constructor(
    val id: UUID,
    val tenantId: UUID,
    /** Insiden pemicu, bila broadcast lahir dari gangguan; `null` untuk siaran ad-hoc kelak. */
    val incidentId: UUID?,
    val channel: NotificationChannel,
    val message: String,
    val createdBy: UUID,
    val createdAt: Instant,
) {
    private val _recipients = mutableListOf<BroadcastRecipient>()

    val recipients: List<BroadcastRecipient> get() = _recipients.toList()

    val recipientCount: Int get() = _recipients.size
    val sentCount: Int get() = _recipients.count { it.status == DeliveryStatus.SENT }
    val skippedCount: Int get() = _recipients.count { it.status == DeliveryStatus.SKIPPED }
    val failedCount: Int get() = _recipients.count { it.status == DeliveryStatus.FAILED }

    /** Mencatat hasil pengiriman ke satu kontak selagi broadcast masih disusun. */
    @Suppress("LongParameterList")
    fun record(
        customerId: UUID?,
        customerName: String,
        phone: String?,
        status: DeliveryStatus,
        detail: String?,
        at: Instant = Instant.now(),
    ) {
        _recipients += BroadcastRecipient.of(tenantId, id, customerId, customerName, phone, status, detail, at)
    }

    companion object {
        fun compose(
            tenantId: UUID,
            incidentId: UUID?,
            channel: NotificationChannel,
            message: String,
            createdBy: UUID,
            at: Instant = Instant.now(),
        ): Broadcast = Broadcast(UuidV7.generate(), tenantId, incidentId, channel, message, createdBy, at)

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            incidentId: UUID?,
            channel: NotificationChannel,
            message: String,
            createdBy: UUID,
            createdAt: Instant,
            recipients: List<BroadcastRecipient>,
        ): Broadcast = Broadcast(id, tenantId, incidentId, channel, message, createdBy, createdAt)
            .apply { _recipients += recipients }
    }
}
