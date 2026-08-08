package com.duluin.ftth.notification.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Penyedia gateway WhatsApp yang bisa dipilih tenant.
 *
 *  - [LOG]          bawaan dev: pesan hanya dicatat ke log, tak pernah keluar. Aman
 *                   untuk mencoba tanpa mengirim WA sungguhan.
 *  - [HTTP_GENERIC] gateway HTTP pihak-ketiga (Fonnte/Wablas/dsb): satu POST form ke
 *                   endpoint tenant, nama field nomor & pesan dapat disetel.
 *  - [META_CLOUD]   WhatsApp Business Cloud API resmi Meta: kirim template ke Graph API.
 */
enum class WhatsAppProvider { LOG, HTTP_GENERIC, META_CLOUD }

/**
 * Gateway WhatsApp yang SUDAH teresolusi & terdekripsi — bentuk siap-pakai yang
 * dikonsumsi dispatcher. Beda dari [NotificationSettings] (yang menyimpan setelan
 * mentah + saklar), tipe ini hanya lahir bila gateway aktif dan konfigurasinya
 * lengkap; kalau tidak, [NotificationSettings.resolveGateway] mengembalikan null.
 */
sealed interface WhatsAppGateway {
    /** Dev: dispatcher cukup mencatat pesan ke log, dianggap terkirim. */
    data object Log : WhatsAppGateway

    /** Gateway HTTP generik: POST form `{phoneField}=nomor&{messageField}=pesan` ke [endpointUrl]. */
    data class HttpGeneric(
        val endpointUrl: String,
        /** Token auth; sebagian gateway menaruhnya di body/bearer, sebagian di URL. Null = tanpa token. */
        val token: String?,
        val phoneField: String,
        val messageField: String,
    ) : WhatsAppGateway

    /** WhatsApp Business Cloud API: kirim ke `/{phoneNumberId}/messages` dengan bearer [accessToken]. */
    data class MetaCloud(
        val phoneNumberId: String,
        val accessToken: String,
        /**
         * Nama template yang disetujui Meta; null = kirim teks bebas (hanya sah dalam jendela
         * 24 jam). Diisi [com.duluin.ftth.notification.application.service.NotificationSender]
         * dari template yang dipetakan ke pemicu, bukan dari setelan gateway.
         */
        val templateName: String?,
        val templateLang: String,
    ) : WhatsAppGateway
}

/**
 * Setelan notifikasi satu tenant (satu baris per tenant): gateway WhatsApp bawa-sendiri
 * (BYO) plus saklar on/off tiap pemicu otomatis.
 *
 * Gateway sengaja per-tenant, bukan milik platform: nomor WA adalah identitas pengirim
 * yang dilihat pelanggan (harus nomor ISP-nya sendiri), mengisolasi risiko blokir antar
 * tenant, dan tiap tenant membayar gateway-nya sendiri. Berbeda dari RADIUS/VPN yang
 * dipusatkan platform karena tak kasat-mata di sisi pelanggan.
 *
 * Token/secret ([httpToken], [metaAccessToken]) plaintext di domain; adapter persistence
 * yang mengenkripsi ke DB — sama seperti secret CoA BRAS. Pada [update], token null/kosong
 * berarti "biarkan apa adanya" agar sunting field lain tak menghapus rahasia tanpa sengaja.
 *
 * Default aman: provider [WhatsAppProvider.LOG], gateway MATI, semua saklar pemicu MATI —
 * tenant harus menyalakan pengiriman & tiap pemicu dengan sadar.
 */
class NotificationSettings private constructor(
    val id: UUID,
    val tenantId: UUID,
    provider: WhatsAppProvider,
    gatewayEnabled: Boolean,
    httpEndpointUrl: String?,
    httpToken: String?,
    httpPhoneField: String,
    httpMessageField: String,
    metaPhoneNumberId: String?,
    metaAccessToken: String?,
    metaWabaId: String?,
    notifyOnSubscriptionLifecycle: Boolean,
    notifyOnInvoiceReminder: Boolean,
    notifyOnWorkOrderSchedule: Boolean,
    notifyOnIncidentOpen: Boolean,
) {
    var provider: WhatsAppProvider = provider
        private set

    /** Saklar induk: mati = tak ada pesan keluar apa pun, apa pun saklar pemicunya. */
    var gatewayEnabled: Boolean = gatewayEnabled
        private set

    var httpEndpointUrl: String? = httpEndpointUrl
        private set

    /** Plaintext di domain; terenkripsi di batas persistence. Null = belum diisi. */
    var httpToken: String? = httpToken
        private set

    /** Nama field nomor tujuan pada body form gateway (Fonnte `target`, Wablas `phone`). */
    var httpPhoneField: String = httpPhoneField
        private set

    /** Nama field isi pesan pada body form gateway (umumnya `message`). */
    var httpMessageField: String = httpMessageField
        private set

    var metaPhoneNumberId: String? = metaPhoneNumberId
        private set

    /** Plaintext di domain; terenkripsi di batas persistence (cermin [httpToken]). */
    var metaAccessToken: String? = metaAccessToken
        private set

    /**
     * WhatsApp Business Account ID — id publik akun bisnis (bukan rahasia), dipakai untuk
     * menarik daftar template dari Graph API. Null = fitur "Tarik dari Meta" belum bisa.
     */
    var metaWabaId: String? = metaWabaId
        private set

    var notifyOnSubscriptionLifecycle: Boolean = notifyOnSubscriptionLifecycle
        private set

    var notifyOnInvoiceReminder: Boolean = notifyOnInvoiceReminder
        private set

    var notifyOnWorkOrderSchedule: Boolean = notifyOnWorkOrderSchedule
        private set

    var notifyOnIncidentOpen: Boolean = notifyOnIncidentOpen
        private set

    @Suppress("LongParameterList")
    fun update(
        provider: WhatsAppProvider,
        gatewayEnabled: Boolean,
        httpEndpointUrl: String?,
        httpToken: String?,
        httpPhoneField: String?,
        httpMessageField: String?,
        metaPhoneNumberId: String?,
        metaAccessToken: String?,
        metaWabaId: String?,
        notifyOnSubscriptionLifecycle: Boolean,
        notifyOnInvoiceReminder: Boolean,
        notifyOnWorkOrderSchedule: Boolean,
        notifyOnIncidentOpen: Boolean,
    ) {
        this.provider = provider
        this.gatewayEnabled = gatewayEnabled
        this.httpEndpointUrl = validateEndpointUrl(httpEndpointUrl)
        // Null/kosong = biarkan apa adanya, agar rahasia tak terhapus saat menyunting field lain.
        httpToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.httpToken = validateToken(it, "Token gateway HTTP", MAX_HTTP_TOKEN) }
        this.httpPhoneField = validateFieldName(httpPhoneField, DEFAULT_PHONE_FIELD, "Field nomor")
        this.httpMessageField = validateFieldName(httpMessageField, DEFAULT_MESSAGE_FIELD, "Field pesan")
        this.metaPhoneNumberId = validatePhoneNumberId(metaPhoneNumberId)
        metaAccessToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.metaAccessToken = validateToken(it, "Access token Meta", MAX_META_TOKEN) }
        this.metaWabaId = validateWabaId(metaWabaId)
        this.notifyOnSubscriptionLifecycle = notifyOnSubscriptionLifecycle
        this.notifyOnInvoiceReminder = notifyOnInvoiceReminder
        this.notifyOnWorkOrderSchedule = notifyOnWorkOrderSchedule
        this.notifyOnIncidentOpen = notifyOnIncidentOpen
    }

    /** Apakah pemicu ini boleh mengirim untuk tenant ini. `MANUAL` selalu boleh. */
    fun isTriggerEnabled(trigger: NotificationTrigger): Boolean = when (trigger) {
        NotificationTrigger.MANUAL -> true
        NotificationTrigger.SUBSCRIPTION_ACTIVATED,
        NotificationTrigger.SUBSCRIPTION_ISOLATED,
        NotificationTrigger.SUBSCRIPTION_TERMINATED,
        -> notifyOnSubscriptionLifecycle
        NotificationTrigger.INVOICE_DUE_SOON,
        NotificationTrigger.INVOICE_OVERDUE,
        -> notifyOnInvoiceReminder
        NotificationTrigger.WORK_ORDER_SCHEDULED -> notifyOnWorkOrderSchedule
        NotificationTrigger.INCIDENT_OPENED -> notifyOnIncidentOpen
    }

    /**
     * Bentuk gateway siap-pakai untuk dispatcher, atau null bila gateway mati atau
     * konfigurasinya belum lengkap (mis. provider META_CLOUD tapi token belum diisi).
     * Null = pemanggil mencatat penerima sebagai SKIPPED "gateway nonaktif".
     */
    fun resolveGateway(): WhatsAppGateway? {
        if (!gatewayEnabled) return null
        return when (provider) {
            WhatsAppProvider.LOG -> WhatsAppGateway.Log
            WhatsAppProvider.HTTP_GENERIC -> {
                val url = httpEndpointUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                WhatsAppGateway.HttpGeneric(url, httpToken, httpPhoneField, httpMessageField)
            }
            WhatsAppProvider.META_CLOUD -> {
                val phoneId = metaPhoneNumberId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val token = metaAccessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                // Template SENGAJA null di sini: pilihannya bergantung pemicu, jadi diisi
                // NotificationSender dari pemetaan pemicu→template. Tanpa pemetaan = teks biasa.
                WhatsAppGateway.MetaCloud(phoneId, token, templateName = null, templateLang = DEFAULT_TEMPLATE_LANG)
            }
        }
    }

    /**
     * Prasyarat pengelolaan template terpenuhi? Yaitu gateway menyala, penyedianya Meta Cloud
     * resmi, dan kredensialnya (Phone Number ID + access token) SUDAH TERSIMPAN. Satu sumber
     * kebenaran untuk penjagaan di service maupun status yang ditampilkan ke UI.
     */
    fun metaTemplateReady(): Boolean =
        gatewayEnabled &&
            provider == WhatsAppProvider.META_CLOUD &&
            !metaPhoneNumberId.isNullOrBlank() &&
            !metaAccessToken.isNullOrBlank()

    companion object {
        const val DEFAULT_PHONE_FIELD = "target"
        const val DEFAULT_MESSAGE_FIELD = "message"
        const val DEFAULT_TEMPLATE_LANG = "id"
        private const val MAX_HTTP_TOKEN = 255
        private const val MAX_META_TOKEN = 1024

        /** Setelan bawaan tenant yang belum pernah menyetel — LOG, gateway & semua pemicu MATI. */
        fun defaultFor(tenantId: UUID): NotificationSettings = NotificationSettings(
            id = UuidV7.generate(),
            tenantId = tenantId,
            provider = WhatsAppProvider.LOG,
            gatewayEnabled = false,
            httpEndpointUrl = null,
            httpToken = null,
            httpPhoneField = DEFAULT_PHONE_FIELD,
            httpMessageField = DEFAULT_MESSAGE_FIELD,
            metaPhoneNumberId = null,
            metaAccessToken = null,
            metaWabaId = null,
            notifyOnSubscriptionLifecycle = false,
            notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false,
            notifyOnIncidentOpen = false,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            provider: WhatsAppProvider,
            gatewayEnabled: Boolean,
            httpEndpointUrl: String?,
            httpToken: String?,
            httpPhoneField: String,
            httpMessageField: String,
            metaPhoneNumberId: String?,
            metaAccessToken: String?,
            metaWabaId: String?,
            notifyOnSubscriptionLifecycle: Boolean,
            notifyOnInvoiceReminder: Boolean,
            notifyOnWorkOrderSchedule: Boolean,
            notifyOnIncidentOpen: Boolean,
        ): NotificationSettings = NotificationSettings(
            id, tenantId, provider, gatewayEnabled, httpEndpointUrl, httpToken,
            httpPhoneField, httpMessageField, metaPhoneNumberId, metaAccessToken,
            metaWabaId, notifyOnSubscriptionLifecycle,
            notifyOnInvoiceReminder, notifyOnWorkOrderSchedule, notifyOnIncidentOpen,
        )

        private fun validateEndpointUrl(url: String?): String? {
            val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 500) throw ValidationException("URL endpoint gateway maksimal 500 karakter")
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                throw ValidationException("URL endpoint gateway harus diawali http:// atau https://")
            }
            return trimmed
        }

        private fun validateToken(token: String, label: String, max: Int): String {
            if (token.length > max) throw ValidationException("$label maksimal $max karakter")
            return token
        }

        private fun validateFieldName(name: String?, default: String, label: String): String {
            val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            if (trimmed.length > 50) throw ValidationException("$label maksimal 50 karakter")
            return trimmed
        }

        private fun validatePhoneNumberId(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 64) throw ValidationException("Phone Number ID Meta maksimal 64 karakter")
            return trimmed
        }

        private fun validateWabaId(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 64) throw ValidationException("WhatsApp Business Account ID maksimal 64 karakter")
            return trimmed
        }
    }
}
