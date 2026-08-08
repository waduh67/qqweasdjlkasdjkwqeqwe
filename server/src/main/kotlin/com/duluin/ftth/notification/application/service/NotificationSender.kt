package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.BroadcastRepository
import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.domain.model.Broadcast
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationChannel
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Titik-masuk tunggal semua pengiriman notifikasi — dipakai baik broadcast manual
 * operator maupun pemicu otomatis (listener langganan/tagihan/WO/insiden). Menyatukan
 * empat keputusan di satu tempat agar tiap pemicu tak mengulang-ulangnya:
 *
 *  1. APAKAH boleh kirim — saklar pemicu tenant ([NotificationSettings.isTriggerEnabled]).
 *  2. LEWAT MANA — resolusi gateway aktif ([NotificationSettings.resolveGateway]), plus
 *     template WhatsApp yang dipetakan ke pemicu ini bila penyedianya Meta Cloud.
 *  3. KIRIM — per penerima; nomor kosong / gateway mati ⇒ SKIPPED (bukan gagal).
 *  4. CATAT — simpan [Broadcast] append-only sebagai riwayat, ditandai pemicunya.
 *
 * Dipanggil dari listener lintas-modul lewat [TenantContext.runAs], jadi ia membaca
 * tenant dari [TenantContext] (bukan dari security context yang absen di scheduler/listener).
 */
@Component
class NotificationSender(
    private val settingsRepo: NotificationSettingsRepository,
    private val broadcastRepo: BroadcastRepository,
    private val templateRepo: NotificationTemplateRepository,
    private val dispatcher: MessageDispatcher,
) {
    /** Satu penerima: pelanggan (id opsional untuk siaran non-pelanggan) + nomor tujuannya. */
    data class Recipient(val customerId: UUID?, val name: String, val phone: String?)

    /**
     * Kirim [message] ke [recipients] bila [trigger] aktif untuk tenant, lalu catat sebagai
     * [Broadcast]. Mengembalikan broadcast tersimpan, atau `null` bila pemicu dimatikan tenant
     * (tak ada apa pun dikirim maupun dicatat). `MANUAL` selalu aktif → tak pernah null.
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

        // Diresolusi sekali per batch: null = gateway mati/kurang konfigurasi → semua SKIPPED.
        val gateway = settings.resolveGateway()?.withTemplateFor(trigger)
        val broadcast = Broadcast.compose(tenantId, incidentId, channel, message, createdBy, trigger)
        recipients.forEach { r ->
            val outcome = when {
                gateway == null -> DeliveryOutcome(DeliveryStatus.SKIPPED, "Gateway WA nonaktif")
                r.phone.isNullOrBlank() -> DeliveryOutcome(DeliveryStatus.SKIPPED, "Nomor telepon kosong")
                else -> dispatcher.send(gateway, r.phone, r.name, message)
            }
            broadcast.record(r.customerId, r.name, r.phone, outcome.status, outcome.detail)
        }
        return broadcastRepo.save(broadcast)
    }

    /**
     * Lengkapi gateway resmi dengan template yang dipetakan ke [trigger]. Pemicu tanpa pemetaan
     * sengaja dibiarkan tanpa template — mematikan pemetaan tak boleh membungkam notifikasi.
     * Akibatnya berbeda per penyedia, dan itu diputuskan dispatcher, bukan di sini: Meta jatuh ke
     * teks biasa, sedangkan Qontak melaporkan SKIPPED karena API-nya memang hanya menerima
     * template. Gateway LOG/HTTP tak mengenal template, jadi dilewatkan apa adanya.
     *
     * Qontak mengacu template lewat ID, bukan nama; template yang belum punya `remoteId` (belum
     * pernah tersinkron) diperlakukan seperti tak dipetakan.
     */
    private fun WhatsAppGateway.withTemplateFor(trigger: NotificationTrigger): WhatsAppGateway {
        if (this !is WhatsAppGateway.MetaCloud && this !is WhatsAppGateway.Qontak) return this
        val template = templateRepo.findForTrigger(trigger) ?: return this
        return when (this) {
            is WhatsAppGateway.MetaCloud -> copy(templateName = template.name, templateLang = template.language)
            is WhatsAppGateway.Qontak ->
                template.remoteId?.let { copy(templateId = it, templateLang = template.language) } ?: this
            else -> this
        }
    }

    companion object {
        /** Aktor untuk siaran otomatis (bukan operator manusia); kolom created_by tanpa FK. */
        val SYSTEM_ACTOR: UUID = UUID(0L, 0L)
    }
}
