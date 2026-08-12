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
 *  - [QONTAK]       Mekari Qontak, BSP resmi WhatsApp: kirim template lewat Open API-nya.
 *
 * Dua yang terakhir adalah jalur WhatsApp RESMI — hanya keduanya yang mengenal template
 * dan karenanya bisa mengelola katalog template dari aplikasi.
 */
enum class WhatsAppProvider {
    LOG,
    HTTP_GENERIC,
    META_CLOUD,
    QONTAK,
    ;

    /** Penyedia resmi WhatsApp (punya API template) — dipakai untuk membuka kartu template. */
    val official: Boolean get() = this == META_CLOUD || this == QONTAK
}

/**
 * Kredensial SIAP-PAKAI untuk API pengelolaan template di sisi penyedia — cermin
 * [WhatsAppGateway] tapi untuk jalur *manajemen*, bukan jalur *kirim*. Sengaja dipisah:
 * mengelola template butuh WABA ID (Meta) yang tak dipakai saat mengirim, dan mengirim
 * butuh Phone Number ID (Meta) yang tak dipakai saat mengelola.
 *
 * Hanya lahir bila prasyarat lengkap; kalau tidak, [NotificationSettings.resolveTemplateApi]
 * mengembalikan null dan [NotificationSettings.templateBlockedReason] menjelaskan apa yang kurang.
 */
sealed interface TemplateApi {
    data class Meta(val wabaId: String, val accessToken: String) : TemplateApi

    data class Qontak(val accessToken: String, val channelIntegrationId: String) : TemplateApi
}

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

    /**
     * Mekari Qontak: kirim lewat `POST /v1/broadcasts/whatsapp/direct` pada kanal
     * [channelIntegrationId] dengan bearer [accessToken].
     *
     * Qontak mengacu template lewat ID, bukan nama seperti Meta. [templateId] null berarti
     * pemicu belum dipetakan ke template mana pun — dan berbeda dari Meta, itu berarti pesan
     * TAK BISA dikirim sama sekali: API broadcast direct Qontak hanya menerima template, tak
     * ada jalur teks bebas di luar jendela percakapan.
     */
    data class Qontak(
        val accessToken: String,
        val channelIntegrationId: String,
        val templateId: String?,
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
 * Kanal EMAIL ([emailEnabled]) berdiri sejajar tapi berbeda asal-usulnya: SMTP-nya milik
 * PLATFORM (satu untuk semua tenant), jadi yang disetel tenant hanya "pakai atau tidak" —
 * tak ada kredensial email di sini sama sekali.
 *
 * Default aman: provider [WhatsAppProvider.LOG], kedua kanal MATI, semua saklar pemicu MATI —
 * tenant harus menyalakan pengiriman & tiap pemicu dengan sadar.
 */
class NotificationSettings private constructor(
    val id: UUID,
    val tenantId: UUID,
    provider: WhatsAppProvider,
    gatewayEnabled: Boolean,
    emailEnabled: Boolean,
    httpEndpointUrl: String?,
    httpToken: String?,
    httpPhoneField: String,
    httpMessageField: String,
    metaPhoneNumberId: String?,
    metaAccessToken: String?,
    metaWabaId: String?,
    qontakAccessToken: String?,
    qontakChannelIntegrationId: String?,
    notifyOnSubscriptionLifecycle: Boolean,
    notifyOnInvoiceReminder: Boolean,
    notifyOnWorkOrderSchedule: Boolean,
    notifyOnIncidentOpen: Boolean,
) {
    var provider: WhatsAppProvider = provider
        private set

    /** Saklar induk kanal WhatsApp: mati = tak ada pesan WA keluar, apa pun saklar pemicunya. */
    var gatewayEnabled: Boolean = gatewayEnabled
        private set

    /**
     * Saklar kanal EMAIL, sejajar dengan [gatewayEnabled] dan bebas satu sama lain: ISP boleh
     * memakai email saja (belum punya gateway WA), WA saja, atau keduanya sekaligus.
     *
     * Tak ada kredensial menyertainya karena SMTP-nya milik platform — yang disetel tenant
     * hanyalah "mau dipakai atau tidak". Konsekuensinya alamat pengirim adalah alamat
     * platform; nama ISP-lah yang tampil sebagai nama pengirim supaya pelanggan tetap
     * mengenali dari siapa emailnya.
     */
    var emailEnabled: Boolean = emailEnabled
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

    /** Plaintext di domain; terenkripsi di batas persistence (cermin [metaAccessToken]). */
    var qontakAccessToken: String? = qontakAccessToken
        private set

    /**
     * UUID kanal WhatsApp di Qontak (`channel_integration_id`) — dipilih operator dari daftar
     * yang ditarik lewat `GET /v1/integrations?target_channel=wa`, bukan diketik manual.
     */
    var qontakChannelIntegrationId: String? = qontakChannelIntegrationId
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
        emailEnabled: Boolean,
        httpEndpointUrl: String?,
        httpToken: String?,
        httpPhoneField: String?,
        httpMessageField: String?,
        metaPhoneNumberId: String?,
        metaAccessToken: String?,
        metaWabaId: String?,
        qontakAccessToken: String?,
        qontakChannelIntegrationId: String?,
        notifyOnSubscriptionLifecycle: Boolean,
        notifyOnInvoiceReminder: Boolean,
        notifyOnWorkOrderSchedule: Boolean,
        notifyOnIncidentOpen: Boolean,
    ) {
        this.provider = provider
        this.gatewayEnabled = gatewayEnabled
        this.emailEnabled = emailEnabled
        this.httpEndpointUrl = validateEndpointUrl(httpEndpointUrl)
        // Null/kosong = biarkan apa adanya, agar rahasia tak terhapus saat menyunting field lain.
        httpToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.httpToken = validateToken(it, "Token gateway HTTP", MAX_HTTP_TOKEN) }
        this.httpPhoneField = validateFieldName(httpPhoneField, DEFAULT_PHONE_FIELD, "Field nomor")
        this.httpMessageField = validateFieldName(httpMessageField, DEFAULT_MESSAGE_FIELD, "Field pesan")
        this.metaPhoneNumberId = validatePhoneNumberId(metaPhoneNumberId)
        metaAccessToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.metaAccessToken = validateToken(it, "Access token Meta", MAX_META_TOKEN) }
        this.metaWabaId = validateWabaId(metaWabaId)
        qontakAccessToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.qontakAccessToken = validateToken(it, "Access token Qontak", MAX_META_TOKEN) }
        this.qontakChannelIntegrationId = validateChannelIntegrationId(qontakChannelIntegrationId)
        this.notifyOnSubscriptionLifecycle = notifyOnSubscriptionLifecycle
        this.notifyOnInvoiceReminder = notifyOnInvoiceReminder
        this.notifyOnWorkOrderSchedule = notifyOnWorkOrderSchedule
        this.notifyOnIncidentOpen = notifyOnIncidentOpen
    }

    /**
     * Apakah pemicu ini boleh mengirim untuk tenant ini. `MANUAL` selalu boleh.
     *
     * `PORTAL_PASSWORD_RESET` juga selalu boleh, dan itu disengaja: pemulihan akun bukan
     * notifikasi pemasaran melainkan bagian dari mekanisme masuk. ISP yang mematikan
     * pemberitahuan langganan tak boleh diam-diam ikut mematikan satu-satunya jalan
     * pelanggannya kembali ke akunnya sendiri.
     *
     * `TENANT_SIGNED_UP` selalu boleh karena penerimanya bukan pelanggan tenant ini melainkan
     * admin ISP-nya sendiri, dan saklar tenant belum ada saat email itu berangkat.
     */
    fun isTriggerEnabled(trigger: NotificationTrigger): Boolean = when (trigger) {
        NotificationTrigger.MANUAL,
        NotificationTrigger.PORTAL_PASSWORD_RESET,
        NotificationTrigger.TENANT_SIGNED_UP,
        -> true
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
     * Kanal yang dipakai pemicu OTOMATIS. Keduanya menyala = pesan yang sama berangkat lewat
     * WhatsApp DAN email, masing-masing dengan catatan riwayatnya sendiri; itu pilihan sadar
     * tenant, bukan efek samping.
     *
     * Bila tak satu pun menyala, jawabannya tetap WhatsApp — bukan "tak usah kirim". Pemicu
     * yang menyala harus meninggalkan jejak: riwayat mencatatnya SKIPPED beserta alasannya,
     * sehingga operator yang bertanya "kenapa pelanggan tak dapat pesan?" menemukan
     * jawabannya di riwayat alih-alih menghadapi kekosongan.
     */
    fun activeChannels(): List<NotificationChannel> = buildList {
        if (gatewayEnabled) add(NotificationChannel.WHATSAPP)
        if (emailEnabled) add(NotificationChannel.EMAIL)
    }.ifEmpty { listOf(NotificationChannel.WHATSAPP) }

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
            WhatsAppProvider.QONTAK -> {
                val token = qontakAccessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val channel = qontakChannelIntegrationId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                // templateId SENGAJA null di sini, sama alasannya dengan cabang Meta di atas.
                WhatsAppGateway.Qontak(token, channel, templateId = null, templateLang = DEFAULT_TEMPLATE_LANG)
            }
        }
    }

    /**
     * Kredensial API template siap-pakai, atau null bila prasyarat belum terpenuhi. Prasyarat
     * dihitung dari setelan yang SUDAH TERSIMPAN, jadi token yang baru diketik di form tapi
     * belum disimpan memang belum membuka pengelolaan template.
     *
     * Berpasangan dengan [templateBlockedReason]: satu memberi kredensialnya, satu memberi
     * alasannya bila tak ada — keduanya membaca kondisi yang sama persis.
     */
    fun resolveTemplateApi(): TemplateApi? {
        if (templateBlockedReason() != null) return null
        return when (provider) {
            WhatsAppProvider.META_CLOUD -> TemplateApi.Meta(metaWabaId!!.trim(), metaAccessToken!!.trim())
            WhatsAppProvider.QONTAK ->
                TemplateApi.Qontak(qontakAccessToken!!.trim(), qontakChannelIntegrationId!!.trim())
            else -> null
        }
    }

    /**
     * Apa yang menghalangi pengelolaan template, sebagai kalimat siap-tampil — atau null bila
     * tak ada halangan. Satu sumber kebenaran untuk penjagaan di service maupun kunci di UI,
     * supaya keduanya tak pernah berbeda pendapat.
     *
     * Berbeda dari [resolveGateway], WABA ID Meta termasuk prasyarat WAJIB di sini: tanpa itu
     * tak ada satu pun operasi template yang bisa dilakukan (semuanya beralamat ke WABA).
     */
    @Suppress("ReturnCount")
    fun templateBlockedReason(): String? {
        if (!gatewayEnabled) return "Gateway WhatsApp masih nonaktif — nyalakan dulu di kartu Gateway WhatsApp."
        if (!provider.official) {
            return "Template hanya berlaku untuk WhatsApp resmi — pilih penyedia Meta Cloud atau Mekari Qontak dulu."
        }
        return when (provider) {
            WhatsAppProvider.META_CLOUD -> when {
                metaPhoneNumberId.isNullOrBlank() -> "Phone Number ID Meta belum diisi."
                metaAccessToken.isNullOrBlank() -> "Access token Meta belum tersimpan — simpan setelan gateway dulu."
                metaWabaId.isNullOrBlank() ->
                    "WhatsApp Business Account ID belum diisi — lengkapi di kartu Gateway WhatsApp."
                else -> null
            }
            WhatsAppProvider.QONTAK -> when {
                qontakAccessToken.isNullOrBlank() ->
                    "Access token Qontak belum tersimpan — simpan setelan gateway dulu."
                qontakChannelIntegrationId.isNullOrBlank() ->
                    "Channel WhatsApp Qontak belum dipilih — muat daftar channel di kartu Gateway lalu simpan."
                else -> null
            }
            else -> null
        }
    }

    companion object {
        const val DEFAULT_PHONE_FIELD = "target"
        const val DEFAULT_MESSAGE_FIELD = "message"
        const val DEFAULT_TEMPLATE_LANG = "id"
        private const val MAX_HTTP_TOKEN = 255
        private const val MAX_META_TOKEN = 1024

        /** Setelan bawaan tenant yang belum pernah menyetel — LOG, kedua kanal & semua pemicu MATI. */
        fun defaultFor(tenantId: UUID): NotificationSettings = NotificationSettings(
            id = UuidV7.generate(),
            tenantId = tenantId,
            provider = WhatsAppProvider.LOG,
            gatewayEnabled = false,
            emailEnabled = false,
            httpEndpointUrl = null,
            httpToken = null,
            httpPhoneField = DEFAULT_PHONE_FIELD,
            httpMessageField = DEFAULT_MESSAGE_FIELD,
            metaPhoneNumberId = null,
            metaAccessToken = null,
            metaWabaId = null,
            qontakAccessToken = null,
            qontakChannelIntegrationId = null,
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
            emailEnabled: Boolean,
            httpEndpointUrl: String?,
            httpToken: String?,
            httpPhoneField: String,
            httpMessageField: String,
            metaPhoneNumberId: String?,
            metaAccessToken: String?,
            metaWabaId: String?,
            qontakAccessToken: String?,
            qontakChannelIntegrationId: String?,
            notifyOnSubscriptionLifecycle: Boolean,
            notifyOnInvoiceReminder: Boolean,
            notifyOnWorkOrderSchedule: Boolean,
            notifyOnIncidentOpen: Boolean,
        ): NotificationSettings = NotificationSettings(
            id, tenantId, provider, gatewayEnabled, emailEnabled, httpEndpointUrl, httpToken,
            httpPhoneField, httpMessageField, metaPhoneNumberId, metaAccessToken,
            metaWabaId, qontakAccessToken, qontakChannelIntegrationId, notifyOnSubscriptionLifecycle,
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

        private fun validateChannelIntegrationId(value: String?): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > 64) throw ValidationException("Channel integration ID Qontak maksimal 64 karakter")
            return trimmed
        }
    }
}
