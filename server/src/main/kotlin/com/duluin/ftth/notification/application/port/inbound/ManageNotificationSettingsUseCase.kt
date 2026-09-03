package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.notification.domain.model.WhatsAppProvider

/**
 * Sisi operator dari setelan notifikasi tenant: baca setelan (atau bawaan mati bila
 * belum pernah disetel) dan ubah gateway WA + saklar pemicu.
 */
interface ManageNotificationSettingsUseCase {
    fun get(): NotificationSettingsView
    fun update(command: UpdateNotificationSettingsCommand): NotificationSettingsView
    fun sendFonnteTest(destination: String): FonnteTestResultView

    /**
     * Daftar kanal WhatsApp di akun Qontak untuk dropdown pemilihan, memakai token yang SUDAH
     * TERSIMPAN — token yang baru diketik di form belum bisa dipakai sampai disimpan.
     */
    fun qontakChannels(): List<QontakChannelView>
}

/** Hasil uji Fonnte: accepted berarti penyedia menerima permintaan, bukan jaminan pesan tiba di handset. */
data class FonnteTestResultView(val delivered: Boolean, val detail: String)

/**
 * Setelan notifikasi untuk ditampilkan. Token TAK pernah dikembalikan — hanya penanda
 * apakah sudah terisi ([httpTokenSet]/[metaAccessTokenSet]/[qontakAccessTokenSet]), agar
 * rahasia tak bocor ke UI.
 */
data class NotificationSettingsView(
    val provider: String,
    val gatewayEnabled: Boolean,
    /** Saklar kanal email (SMTP platform), bebas dari [gatewayEnabled]. */
    val emailEnabled: Boolean,
    val httpEndpointUrl: String?,
    val httpTokenSet: Boolean,
    val httpPhoneField: String,
    val httpMessageField: String,
    val metaPhoneNumberId: String?,
    val metaAccessTokenSet: Boolean,
    val metaWabaId: String?,
    val qontakAccessTokenSet: Boolean,
    val qontakChannelIntegrationId: String?,
    /**
     * Prasyarat pengelolaan template terpenuhi (gateway aktif + penyedia resmi + kredensial
     * tersimpan) — UI memakainya untuk membuka/mengunci kartu template. [templateBlockedReason]
     * menjelaskan apa yang kurang bila belum.
     */
    val templateReady: Boolean,
    val templateBlockedReason: String?,
    val notifyOnSubscriptionLifecycle: Boolean,
    val notifyOnInvoiceReminder: Boolean,
    val notifyOnWorkOrderSchedule: Boolean,
    val notifyOnIncidentOpen: Boolean,
)

/** Satu kanal WhatsApp Qontak untuk dropdown: UUID kanal + nama akun yang bisa dikenali operator. */
data class QontakChannelView(val id: String, val name: String)

/**
 * Perubahan setelan. Token ([httpToken]/[metaAccessToken]/[qontakAccessToken]) null/kosong =
 * biarkan apa adanya (tak menimpa yang tersimpan), agar sunting field lain tak menghapus rahasia.
 */
@Suppress("LongParameterList")
data class UpdateNotificationSettingsCommand(
    val provider: WhatsAppProvider,
    val gatewayEnabled: Boolean,
    val emailEnabled: Boolean,
    val httpEndpointUrl: String?,
    val httpToken: String?,
    val httpPhoneField: String?,
    val httpMessageField: String?,
    val metaPhoneNumberId: String?,
    val metaAccessToken: String?,
    val metaWabaId: String?,
    val qontakAccessToken: String?,
    val qontakChannelIntegrationId: String?,
    val notifyOnSubscriptionLifecycle: Boolean,
    val notifyOnInvoiceReminder: Boolean,
    val notifyOnWorkOrderSchedule: Boolean,
    val notifyOnIncidentOpen: Boolean,
)
