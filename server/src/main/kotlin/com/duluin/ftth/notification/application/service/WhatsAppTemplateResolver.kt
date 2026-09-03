package com.duluin.ftth.notification.application.service

import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.springframework.stereotype.Component

/**
 * Melengkapi gateway WhatsApp resmi dengan template yang dipetakan ke sebuah pemicu.
 *
 * Berdiri sendiri (bukan fungsi privat di [NotificationSender]) karena kini ada DUA jalur
 * kirim: siaran biasa lewat [NotificationSender], dan pesan transaksional lewat
 * [NotificationApiService] yang sengaja melewati riwayat broadcast. Kalau aturan pemilihan
 * template disalin ke keduanya, cepat atau lambat keduanya akan berbeda — dan bedanya baru
 * ketahuan ketika pesan sungguhan gagal terkirim ke pelanggan.
 */
@Component
class WhatsAppTemplateResolver(
    private val templateRepo: NotificationTemplateRepository,
) {
    /**
     * Pemicu tanpa pemetaan sengaja dibiarkan tanpa template — mematikan pemetaan tak boleh
     * membungkam notifikasi. Akibatnya berbeda per penyedia, dan itu diputuskan dispatcher,
     * bukan di sini: Meta jatuh ke teks biasa, sedangkan Qontak melaporkan SKIPPED karena
     * API-nya memang hanya menerima template. Gateway LOG/HTTP tak mengenal template, jadi
     * dilewatkan apa adanya.
     *
     * Qontak mengacu template lewat ID, bukan nama; template yang belum punya `remoteId`
     * (belum pernah tersinkron) diperlakukan seperti tak dipetakan.
     */
    fun withTemplateFor(gateway: WhatsAppGateway, trigger: NotificationTrigger): WhatsAppGateway {
        if (gateway !is WhatsAppGateway.MetaCloud && gateway !is WhatsAppGateway.Qontak) return gateway
        val template = templateRepo.findForTrigger(trigger) ?: return gateway
        return when (gateway) {
            is WhatsAppGateway.MetaCloud -> if (
                TemplateAssignmentEligibility.blockedReason(WhatsAppProvider.META_CLOUD, template) == null
            ) {
                gateway.copy(templateName = template.name, templateLang = template.language)
            } else {
                gateway
            }
            is WhatsAppGateway.Qontak -> if (
                TemplateAssignmentEligibility.blockedReason(WhatsAppProvider.QONTAK, template) == null
            ) {
                gateway.copy(templateId = template.remoteId, templateLang = template.language)
            } else {
                gateway
            }
            else -> gateway
        }
    }
}
