package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.notification.domain.model.WhatsAppProvider

/**
 * Sisi operator dari setelan notifikasi tenant: baca setelan (atau bawaan mati bila
 * belum pernah disetel) dan ubah gateway WA + saklar pemicu.
 */
interface ManageNotificationSettingsUseCase {
    fun get(): NotificationSettingsView
    fun update(command: UpdateNotificationSettingsCommand): NotificationSettingsView
}

/**
 * Setelan notifikasi untuk ditampilkan. Token TAK pernah dikembalikan — hanya penanda
 * apakah sudah terisi ([httpTokenSet]/[metaAccessTokenSet]), agar rahasia tak bocor ke UI.
 */
data class NotificationSettingsView(
    val provider: String,
    val gatewayEnabled: Boolean,
    val httpEndpointUrl: String?,
    val httpTokenSet: Boolean,
    val httpPhoneField: String,
    val httpMessageField: String,
    val metaPhoneNumberId: String?,
    val metaAccessTokenSet: Boolean,
    val metaTemplateName: String?,
    val metaTemplateLang: String,
    val notifyOnSubscriptionLifecycle: Boolean,
    val notifyOnInvoiceReminder: Boolean,
    val notifyOnWorkOrderSchedule: Boolean,
    val notifyOnIncidentOpen: Boolean,
)

/**
 * Perubahan setelan. Token ([httpToken]/[metaAccessToken]) null/kosong = biarkan apa
 * adanya (tak menimpa yang tersimpan), agar sunting field lain tak menghapus rahasia.
 */
data class UpdateNotificationSettingsCommand(
    val provider: WhatsAppProvider,
    val gatewayEnabled: Boolean,
    val httpEndpointUrl: String?,
    val httpToken: String?,
    val httpPhoneField: String?,
    val httpMessageField: String?,
    val metaPhoneNumberId: String?,
    val metaAccessToken: String?,
    val metaTemplateName: String?,
    val metaTemplateLang: String?,
    val notifyOnSubscriptionLifecycle: Boolean,
    val notifyOnInvoiceReminder: Boolean,
    val notifyOnWorkOrderSchedule: Boolean,
    val notifyOnIncidentOpen: Boolean,
)
