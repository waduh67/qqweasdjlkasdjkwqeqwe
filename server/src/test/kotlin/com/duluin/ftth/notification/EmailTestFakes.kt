package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.application.port.outbound.EmailSubjectRepository
import com.duluin.ftth.notification.application.port.outbound.OutboundEmail
import com.duluin.ftth.notification.application.port.outbound.PlatformEmailSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.TenantEmailSettingsRepository
import com.duluin.ftth.notification.application.service.EmailBrandingResolver
import com.duluin.ftth.notification.config.MailProperties
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.EmailBranding
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import com.duluin.ftth.notification.domain.model.TenantEmailSettings
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import java.util.UUID

/**
 * Port palsu bersama untuk seluruh uji jalur email. Repo ini tak memakai pustaka mocking
 * sama sekali — fake tulisan tangan lebih jujur menyatakan perilaku yang diandalkan, dan
 * di sini perilaku itu memang cuma satu kalimat per port ("kembalikan baris yang disetel").
 *
 * Dikumpulkan di satu berkas karena rantai email menyentuh empat port sekaligus (setelan
 * platform, timpaan tenant, subjek, pengiriman) dan hampir tiap kelas uji butuh lebih dari
 * satu di antaranya.
 */

/** Nama ISP bawaan di uji; dipakai sebagai nama pengirim saat tenant tak menimpanya. */
internal const val DEMO_TENANT_NAME = "PT Sinar Jaya Net"

/** Setelan platform di memori. `null` = platform belum pernah menyimpan apa pun. */
internal class FakePlatformEmailSettingsRepository(
    var current: PlatformEmailSettings? = null,
) : PlatformEmailSettingsRepository {
    override fun find(): PlatformEmailSettings? = current

    override fun save(settings: PlatformEmailSettings): PlatformEmailSettings {
        current = settings
        return settings
    }
}

/** Timpaan tenant di memori. `null` = tenant mewarisi platform seluruhnya. */
internal class FakeTenantEmailSettingsRepository(
    var current: TenantEmailSettings? = null,
) : TenantEmailSettingsRepository {
    override fun find(): TenantEmailSettings? = current

    override fun save(settings: TenantEmailSettings): TenantEmailSettings {
        current = settings
        return settings
    }
}

/**
 * Dua peta subjek yang bisa disetel terpisah, supaya uji pewarisan bisa menyusun ketiga
 * tingkatnya (tenant → platform → bawaan kode) tanpa menyentuh DB.
 */
internal class FakeEmailSubjectRepository(
    var platform: Map<NotificationTrigger, String> = emptyMap(),
    var tenant: Map<NotificationTrigger, String> = emptyMap(),
) : EmailSubjectRepository {
    override fun platformSubjects(): Map<NotificationTrigger, String> = platform
    override fun tenantSubjects(): Map<NotificationTrigger, String> = tenant

    override fun replacePlatform(subjects: Map<NotificationTrigger, String>) {
        platform = subjects
    }

    override fun replaceTenant(subjects: Map<NotificationTrigger, String>) {
        tenant = subjects
    }
}

/**
 * Hanya [findById] yang dipakai jalur email (nama ISP untuk kop surat); sisanya sengaja
 * meledak agar ketergantungan baru yang menyelinap masuk ketahuan sebagai kegagalan uji,
 * bukan sebagai nilai palsu yang lolos diam-diam.
 */
internal class FakeTenantApi(
    private val tenantId: UUID?,
    private val name: String = DEMO_TENANT_NAME,
) : TenantApi {
    override fun findById(id: UUID): TenantRef? =
        TenantRef(id, "demo", name, TenantStatus.ACTIVE).takeIf { id == tenantId }

    override fun findBySlug(slug: String): TenantRef = notUsed()
    override fun requireById(id: UUID): TenantRef = notUsed()
    override fun platformTenantId(): UUID = notUsed()
    override fun findActiveTenantIds(): List<UUID> = notUsed()
    override fun ensureTenant(slug: String, name: String): TenantRef = notUsed()
    override fun suspend(id: UUID): TenantRef = notUsed()
    override fun activate(id: UUID): TenantRef = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

/**
 * Mencatat surat yang berangkat apa adanya. [failure] mensimulasikan SMTP yang meledak
 * (lemparan, bukan hasil FAILED) — dua keadaan itu ditangani jalur yang berbeda oleh
 * pemanggilnya, jadi keduanya perlu bisa diuji.
 */
internal class RecordingEmailDispatcher(
    private val outcome: DeliveryOutcome = DeliveryOutcome(DeliveryStatus.SENT, "ok"),
    private val failure: Throwable? = null,
) : EmailDispatcher {
    val messages = mutableListOf<OutboundEmail>()

    /** Alamat tujuan tiap surat, urut kirim. */
    val sent: List<String> get() = messages.map { it.to }
    val subjects: List<String> get() = messages.map { it.subject }
    val fromNames: List<String?> get() = messages.map { it.fromName }

    override fun send(message: OutboundEmail): DeliveryOutcome {
        messages += message
        failure?.let { throw it }
        return outcome
    }
}

/**
 * Setelan platform siap-pakai. Lewat [PlatformEmailSettings.rehydrate] alih-alih
 * `default()` + `update()` supaya tiap uji cukup menyebut satu-dua kolom yang benar-benar
 * diamatinya, bukan sebelas parameter yang tak ada hubungannya dengan pertanyaannya.
 */
@Suppress("LongParameterList")
internal fun platformEmailSettings(
    smtpHost: String? = null,
    smtpPort: Int = PlatformEmailSettings.DEFAULT_SMTP_PORT,
    smtpUsername: String? = null,
    smtpPassword: String? = null,
    smtpAuth: Boolean = true,
    smtpStartTls: Boolean = true,
    fromAddress: String? = null,
    fromName: String = PlatformEmailSettings.DEFAULT_FROM_NAME,
    branding: EmailBranding = EmailBranding.EMPTY,
    publicBaseUrl: String? = null,
): PlatformEmailSettings = PlatformEmailSettings.rehydrate(
    id = UuidV7.generate(),
    smtpHost = smtpHost,
    smtpPort = smtpPort,
    smtpUsername = smtpUsername,
    smtpPassword = smtpPassword,
    smtpAuth = smtpAuth,
    smtpStartTls = smtpStartTls,
    fromAddress = fromAddress,
    fromName = fromName,
    branding = branding,
    publicBaseUrl = publicBaseUrl,
)

/** Timpaan tenant siap-pakai; semua kolomnya null berarti "warisi platform seluruhnya". */
internal fun tenantEmailSettings(
    tenantId: UUID,
    fromAddress: String? = null,
    fromName: String? = null,
    branding: EmailBranding = EmailBranding.EMPTY,
): TenantEmailSettings = TenantEmailSettings.rehydrate(
    id = UuidV7.generate(),
    tenantId = tenantId,
    fromAddress = fromAddress,
    fromName = fromName,
    branding = branding,
)

/**
 * Resolver merek dengan keempat ketergantungannya sudah dipalsukan. Bawaannya menggambarkan
 * pemasangan paling polos yang mungkin: platform belum menyetel apa pun, tenant tak menimpa
 * apa pun, URL publik kosong sehingga email berangkat tanpa logo.
 */
internal fun brandingResolver(
    platform: PlatformEmailSettingsRepository = FakePlatformEmailSettingsRepository(),
    tenant: TenantEmailSettingsRepository = FakeTenantEmailSettingsRepository(),
    tenants: TenantApi = FakeTenantApi(null),
    mailProperties: MailProperties = MailProperties(),
): EmailBrandingResolver = EmailBrandingResolver(platform, tenant, tenants, mailProperties)
