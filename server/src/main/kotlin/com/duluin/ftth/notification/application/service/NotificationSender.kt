package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.BroadcastRepository
import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.domain.model.Broadcast
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationChannel
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Titik-masuk tunggal semua pengiriman notifikasi — dipakai baik broadcast manual
 * operator maupun pemicu otomatis (listener langganan/tagihan/WO/insiden). Menyatukan
 * lima keputusan di satu tempat agar tiap pemicu tak mengulang-ulangnya:
 *
 *  1. APAKAH boleh kirim — saklar pemicu tenant ([NotificationSettings.isTriggerEnabled]).
 *  2. LEWAT KANAL APA — WhatsApp, email, atau keduanya ([NotificationSettings.activeChannels]).
 *  3. LEWAT MANA — resolusi gateway aktif ([NotificationSettings.resolveGateway]), plus
 *     template WhatsApp yang dipetakan ke pemicu ini bila penyedianya Meta Cloud.
 *  4. KIRIM — per penerima; alamat kosong / kanal mati ⇒ SKIPPED (bukan gagal).
 *  5. CATAT — simpan [Broadcast] append-only sebagai riwayat, ditandai pemicunya.
 *
 * Satu broadcast = satu kanal. Pesan yang berangkat lewat dua kanal menghasilkan DUA catatan,
 * bukan satu catatan bercabang: keduanya punya hasil kirim yang berbeda per penerima, dan
 * riwayat yang menggabungkannya akan menyembunyikan kanal mana yang sebenarnya gagal.
 *
 * Dipanggil dari listener lintas-modul lewat [TenantContext.runAs], jadi ia membaca
 * tenant dari [TenantContext] (bukan dari security context yang absen di scheduler/listener).
 */
@Component
class NotificationSender(
    private val settingsRepo: NotificationSettingsRepository,
    private val broadcastRepo: BroadcastRepository,
    private val templates: WhatsAppTemplateResolver,
    private val dispatcher: MessageDispatcher,
    private val emailDispatcher: EmailDispatcher,
    private val tenants: TenantApi,
) {
    /**
     * Satu penerima: pelanggan (id opsional untuk siaran non-pelanggan) beserta alamatnya di
     * tiap kanal. Keduanya boleh null — pelanggan yang tak punya alamat di kanal yang dipakai
     * tercatat SKIPPED, bukan menggagalkan siaran untuk yang lain.
     */
    data class Recipient(
        val customerId: UUID?,
        val name: String,
        val phone: String?,
        val email: String? = null,
    )

    /**
     * Kirim lewat kanal yang dipilih tenant untuk pemicu otomatis — jalur yang dipakai
     * seluruh listener. Mengembalikan satu [Broadcast] per kanal (kosong bila pemicu
     * dimatikan tenant: tak ada apa pun dikirim maupun dicatat).
     *
     * Pemanggil sengaja tak boleh menentukan kanal di sini: "lewat mana pelanggan dihubungi"
     * adalah keputusan ISP, bukan keputusan kode yang kebetulan menerbitkan peristiwanya.
     */
    @Transactional
    fun dispatchAuto(
        trigger: NotificationTrigger,
        message: String,
        recipients: List<Recipient>,
        incidentId: UUID? = null,
    ): List<Broadcast> {
        val tenantId = TenantContext.tenantId()
        val settings = settingsRepo.find() ?: NotificationSettings.defaultFor(tenantId)
        if (!settings.isTriggerEnabled(trigger)) return emptyList()
        return settings.activeChannels().map { channel ->
            send(tenantId, settings, trigger, message, recipients, incidentId, SYSTEM_ACTOR, channel)
        }
    }

    /**
     * Kirim [message] ke [recipients] lewat SATU kanal yang ditentukan pemanggil, lalu catat
     * sebagai [Broadcast]. Untuk siaran manual: operator memilih sendiri kanalnya di layar
     * broadcast, jadi kanal itu dipakai apa adanya tanpa menimbang [NotificationSettings.activeChannels].
     *
     * Mengembalikan broadcast tersimpan, atau `null` bila pemicu dimatikan tenant (tak ada apa
     * pun dikirim maupun dicatat). `MANUAL` selalu aktif → tak pernah null.
     */
    @Transactional
    @Suppress("LongParameterList")
    fun dispatch(
        trigger: NotificationTrigger,
        message: String,
        recipients: List<Recipient>,
        incidentId: UUID? = null,
        createdBy: UUID = SYSTEM_ACTOR,
        channel: NotificationChannel = NotificationChannel.WHATSAPP,
    ): Broadcast? {
        val tenantId = TenantContext.tenantId()
        val settings = settingsRepo.find() ?: NotificationSettings.defaultFor(tenantId)
        if (!settings.isTriggerEnabled(trigger)) return null
        return send(tenantId, settings, trigger, message, recipients, incidentId, createdBy, channel)
    }

    @Suppress("LongParameterList")
    private fun send(
        tenantId: UUID,
        settings: NotificationSettings,
        trigger: NotificationTrigger,
        message: String,
        recipients: List<Recipient>,
        incidentId: UUID?,
        createdBy: UUID,
        channel: NotificationChannel,
    ): Broadcast {
        val broadcast = Broadcast.compose(tenantId, incidentId, channel, message, createdBy, trigger)
        when (channel) {
            NotificationChannel.WHATSAPP -> sendWhatsApp(settings, trigger, message, recipients, broadcast)
            NotificationChannel.EMAIL -> sendEmail(tenantId, settings, trigger, message, recipients, broadcast)
        }
        return broadcastRepo.save(broadcast)
    }

    private fun sendWhatsApp(
        settings: NotificationSettings,
        trigger: NotificationTrigger,
        message: String,
        recipients: List<Recipient>,
        broadcast: Broadcast,
    ) {
        // Diresolusi sekali per batch: null = gateway mati/kurang konfigurasi → semua SKIPPED.
        val gateway = settings.resolveGateway()?.let { templates.withTemplateFor(it, trigger) }
        recipients.forEach { r ->
            val outcome = when {
                gateway == null -> DeliveryOutcome(DeliveryStatus.SKIPPED, "Gateway WA nonaktif")
                r.phone.isNullOrBlank() -> DeliveryOutcome(DeliveryStatus.SKIPPED, "Nomor telepon kosong")
                else -> dispatcher.send(gateway, r.phone, r.name, message)
            }
            broadcast.record(r.customerId, r.name, r.phone, outcome.status, outcome.detail)
        }
    }

    /**
     * Kanal email tak punya "gateway" untuk diresolusi — SMTP-nya milik platform. Yang
     * ditimbang hanyalah saklar tenant, sehingga siaran email yang diminta saat kanalnya
     * mati tetap meninggalkan jejak SKIPPED alih-alih diam-diam terkirim.
     */
    private fun sendEmail(
        tenantId: UUID,
        settings: NotificationSettings,
        trigger: NotificationTrigger,
        message: String,
        recipients: List<Recipient>,
        broadcast: Broadcast,
    ) {
        val subject = subjectFor(trigger)
        // Nama ISP, bukan nama platform: lihat alasannya di EmailDispatcher.send.
        val senderName = tenants.findById(tenantId)?.name
        recipients.forEach { r ->
            val outcome = when {
                !settings.emailEnabled -> DeliveryOutcome(DeliveryStatus.SKIPPED, "Kanal email nonaktif")
                r.email.isNullOrBlank() -> DeliveryOutcome(DeliveryStatus.SKIPPED, "Alamat email kosong")
                else -> emailDispatcher.send(r.email, subject, message, senderName)
            }
            broadcast.record(r.customerId, r.name, r.email, outcome.status, outcome.detail)
        }
    }

    /**
     * Baris subjek per pemicu. Isi pesannya sendiri sudah dirangkai pemanggil dan dipakai apa
     * adanya di badan email — hanya subjek yang perlu dijahit di sini karena WhatsApp tak
     * mengenal padanannya.
     */
    private fun subjectFor(trigger: NotificationTrigger): String = when (trigger) {
        NotificationTrigger.SUBSCRIPTION_ACTIVATED -> "Layanan internet Anda sudah aktif"
        NotificationTrigger.SUBSCRIPTION_ISOLATED -> "Layanan internet Anda dinonaktifkan sementara"
        NotificationTrigger.SUBSCRIPTION_TERMINATED -> "Layanan internet Anda telah dihentikan"
        NotificationTrigger.INVOICE_DUE_SOON -> "Tagihan internet Anda akan jatuh tempo"
        NotificationTrigger.INVOICE_OVERDUE -> "Tagihan internet Anda telah melewati jatuh tempo"
        NotificationTrigger.WORK_ORDER_SCHEDULED -> "Jadwal kunjungan teknisi"
        NotificationTrigger.INCIDENT_OPENED -> "Pemberitahuan gangguan layanan"
        NotificationTrigger.MANUAL -> "Pemberitahuan dari penyedia layanan internet Anda"
        // Tak pernah lewat sini (jalurnya NotificationApi, tanpa riwayat) — cabang ini ada
        // supaya menambah pemicu baru memaksa memikirkan subjeknya, bukan diam-diam lolos.
        NotificationTrigger.PORTAL_PASSWORD_RESET -> "Kode pemulihan password"
    }

    companion object {
        /** Aktor untuk siaran otomatis (bukan operator manusia); kolom created_by tanpa FK. */
        val SYSTEM_ACTOR: UUID = UUID(0L, 0L)
    }
}
