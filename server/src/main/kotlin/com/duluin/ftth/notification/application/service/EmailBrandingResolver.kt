package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.PlatformEmailSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.TenantEmailSettingsRepository
import com.duluin.ftth.notification.config.MailProperties
import com.duluin.ftth.notification.domain.model.EmailBranding
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Identitas satu surat setelah SELURUH pewarisan diselesaikan: siapa pengirimnya, ke mana
 * balasannya, dan seperti apa bungkusnya. Bentuk ini yang dibawa ke [EmailRenderer] —
 * mulai dari sini tak ada lagi pertanyaan "ini punya tenant atau punya platform".
 */
data class ResolvedEmailIdentity(
    /** Alamat `From` — SELALU milik platform; null = pakai `ftth.mail.from` dari env di adapter. */
    val fromAddress: String?,
    val fromName: String,
    /** Alamat `Reply-To`; hanya terisi bila tenant menyetel alamat balasannya sendiri. */
    val replyTo: String?,
    val branding: EmailBranding,
    /** URL absolut logo, atau null bila tak ada logo / URL publik belum disetel. */
    val logoUrl: String?,
)

/**
 * Menjahit setelan email PLATFORM dengan timpaan TENANT jadi satu [ResolvedEmailIdentity].
 *
 * Aturannya cuma satu dan tinggal di [EmailBranding.overriddenBy]: yang diisi tenant menang,
 * yang kosong mewarisi platform. Yang dikerjakan di sini adalah dua hal yang tak bisa
 * dijawab domain sendirian — nama ISP diambil dari module tenancy, dan URL logo dirangkai
 * dari base URL yang mungkin datang dari DB atau dari env.
 *
 * Nama pengirim punya rantai tiga tingkat yang disengaja: timpaan tenant → NAMA PERUSAHAAN
 * tenant → nama platform. Tingkat tengah itu penting; tanpanya pelanggan menerima tagihan
 * internetnya dari nama yang tak pernah ia kenal, yang lebih mirip penipuan daripada
 * pemberitahuan.
 *
 * ALAMAT pengirim justru sebaliknya — satu tingkat, tanpa timpaan. Relay platform hanya
 * menerima pengirim yang sudah terverifikasi di sisi penyedia, jadi alamat berdomain tenant
 * membuat suratnya ditolak sebelum berangkat. Yang tersisa untuk tenant adalah `Reply-To`.
 */
@Component
class EmailBrandingResolver(
    private val platformRepo: PlatformEmailSettingsRepository,
    private val tenantRepo: TenantEmailSettingsRepository,
    private val tenants: TenantApi,
    private val mailProperties: MailProperties,
) {
    /** Setelan platform apa adanya (baris bawaan bila belum pernah disimpan). */
    @Transactional(readOnly = true)
    fun platformSettings(): PlatformEmailSettings = platformRepo.find() ?: PlatformEmailSettings.default()

    /**
     * Identitas untuk surat yang BUKAN atas nama tenant mana pun — peringatan job macet ke
     * operator platform. Sengaja tak menyentuh repo tenant sama sekali: pemanggilnya berjalan
     * di utas penjaga tanpa konteks tenant, dan sebuah query ber-RLS di sana akan meledak.
     */
    @Transactional(readOnly = true)
    fun platformOnly(): ResolvedEmailIdentity {
        val platform = platformSettings()
        return ResolvedEmailIdentity(
            fromAddress = platform.fromAddress,
            fromName = platform.fromName,
            replyTo = null,
            branding = platform.branding,
            logoUrl = logoUrl(platform, tenantId = null, tenantHasLogo = false),
        )
    }

    /**
     * Identitas untuk surat atas nama [tenantId]. Dipanggil dalam konteks tenant tersebut
     * (RLS menyaring baris timpaannya).
     */
    @Transactional(readOnly = true)
    fun forTenant(tenantId: UUID): ResolvedEmailIdentity {
        val platform = platformSettings()
        val override = tenantRepo.find()
        val tenantHasLogo = override?.branding?.logoSet == true
        return ResolvedEmailIdentity(
            // Selalu alamat platform, tanpa jalan timpaan: relay-nya hanya menerima pengirim
            // yang sudah terverifikasi di sisi penyedia, jadi alamat berdomain tenant bukan
            // sekadar berisiko masuk spam — suratnya batal berangkat.
            fromAddress = platform.fromAddress,
            fromName = override?.fromName
                ?: tenants.findById(tenantId)?.name
                ?: platform.fromName,
            // Di sinilah alamat tenant tetap berguna: `Reply-To` tak diverifikasi penyedia
            // mana pun, jadi balasan pelanggan bisa mendarat di ISP-nya walau suratnya
            // berangkat dari alamat platform.
            replyTo = override?.replyToAddress,
            branding = platform.branding.overriddenBy(override?.branding ?: EmailBranding.EMPTY),
            logoUrl = logoUrl(platform, tenantId, tenantHasLogo),
        )
    }

    /**
     * URL absolut logo. Null bila tak ada logo sama sekali atau URL publik belum disetel —
     * dan itu bukan kegagalan: layout tetap terbaca utuh tanpa logo, karena banyak klien
     * email memblokir gambar remote secara bawaan sehingga logo memang cuma hiasan.
     *
     * Publik supaya layar setelan bisa menyusun pratinjaunya dari objek yang sudah dipegangnya
     * tanpa menembak repo sekali lagi hanya untuk merangkai satu string.
     */
    fun logoUrl(platform: PlatformEmailSettings, tenantId: UUID? = null, tenantHasLogo: Boolean = false): String? {
        val base = publicBaseUrl(platform) ?: return null
        return when {
            tenantHasLogo && tenantId != null -> "$base$PUBLIC_LOGO_PATH/$tenantId"
            platform.branding.logoSet -> "$base$PUBLIC_LOGO_PATH"
            else -> null
        }
    }

    /**
     * Alamat pangkal aplikasi untuk tautan di dalam surat: baris DB dulu (bisa diubah admin
     * platform tanpa deploy), lalu `ftth.mail.public-base-url` dari env. Null bila tak disetel
     * di mana pun — tautannya lalu dilewati, bukan dirakit setengah jadi.
     */
    fun publicBaseUrl(platform: PlatformEmailSettings = platformSettings()): String? =
        (platform.publicBaseUrl ?: mailProperties.publicBaseUrl.trim().takeIf { it.isNotEmpty() })?.trimEnd('/')

    /** Identitas untuk tenant yang sedang aktif di [TenantContext]. */
    @Transactional(readOnly = true)
    fun forCurrentTenant(): ResolvedEmailIdentity = forTenant(TenantContext.tenantId())

    companion object {
        /** Rute publik penyaji logo; dipakai bersama `PublicEmailLogoController`. */
        const val PUBLIC_LOGO_PATH = "/api/public/email-logo"
    }
}
