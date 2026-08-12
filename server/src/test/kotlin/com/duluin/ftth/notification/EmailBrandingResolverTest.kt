package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.notification.application.port.outbound.TenantEmailSettingsRepository
import com.duluin.ftth.notification.application.service.EmailBrandingResolver
import com.duluin.ftth.notification.config.MailProperties
import com.duluin.ftth.notification.domain.model.EmailBranding
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import com.duluin.ftth.notification.domain.model.TenantEmailSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Pewarisan identitas & merek email. Yang diuji bukan sekadar "field tersalin", melainkan
 * lima janji yang dipegang layar setelan: timpaan tenant menang, kolom kosong benar-benar
 * mewarisi platform (bukan mengosongkan), nama pengirim jatuh ke NAMA ISP sebelum ke nama
 * platform, alamat pengirim TAK PERNAH ikut ditimpa (relay hanya menerima pengirim
 * terverifikasi), dan jalur peringatan job tak pernah menyentuh baris tenant mana pun.
 */
class EmailBrandingResolverTest {

    private val tenantId: UUID = UuidV7.generate()

    private val platformBranding = EmailBranding(
        logoStorageKey = "platform/email/logo",
        logoContentType = "image/png",
        accentColor = "#123456",
        footerText = "Duluin.net · Jl. Merdeka 1",
        signatureText = "Salam, Tim Platform",
    )

    @Test
    fun `timpaan tenant menang atas bawaan platform`() {
        val resolver = resolver(
            platform = platformEmailSettings(
                fromAddress = "no-reply@duluin.net",
                fromName = "NetOps Console",
                branding = platformBranding,
                publicBaseUrl = "https://app.duluin.net",
            ),
            tenant = tenantEmailSettings(
                tenantId,
                replyToAddress = "billing@sinarjaya.id",
                fromName = "Sinar Jaya Support",
                branding = EmailBranding(
                    logoStorageKey = "$tenantId/email/logo",
                    logoContentType = "image/svg+xml",
                    accentColor = "#ff8800",
                    footerText = null,
                    signatureText = "Salam, CS Sinar Jaya",
                ),
            ),
        )

        val identity = resolver.forTenant(tenantId)

        // Alamat pengirim TIDAK ikut ditimpa — lihat test di bawah untuk alasannya.
        assertThat(identity.fromAddress).isEqualTo("no-reply@duluin.net")
        assertThat(identity.fromName).isEqualTo("Sinar Jaya Support")
        // Reply-To lahir saat tenant menyetel alamat balasannya sendiri.
        assertThat(identity.replyTo).isEqualTo("billing@sinarjaya.id")
        assertThat(identity.branding.accentColor).isEqualTo("#ff8800")
        assertThat(identity.branding.signatureText).isEqualTo("Salam, CS Sinar Jaya")
        // Footer tak diisi tenant ⇒ tetap footer platform, bukan hilang.
        assertThat(identity.branding.footerText).isEqualTo("Duluin.net · Jl. Merdeka 1")
        assertThat(identity.logoUrl).isEqualTo("https://app.duluin.net/api/public/email-logo/$tenantId")
    }

    @Test
    fun `alamat tenant tak pernah jadi From, cuma jadi Reply-To`() {
        // Penjaga regresi, bukan sekadar penegasan bentuk: relay platform hanya menerima
        // pengirim yang sudah terverifikasi di sisi penyedia, jadi begitu alamat berdomain
        // tenant bocor ke header From, SELURUH email ISP itu gagal berangkat — bukan sekadar
        // mendarat di folder spam.
        val resolver = resolver(
            platform = platformEmailSettings(fromAddress = "no-reply@duluin.net"),
            tenant = tenantEmailSettings(tenantId, replyToAddress = "billing@sinarjaya.id"),
        )

        val identity = resolver.forTenant(tenantId)

        assertThat(identity.fromAddress).isEqualTo("no-reply@duluin.net")
        assertThat(identity.replyTo).isEqualTo("billing@sinarjaya.id")
    }

    @Test
    fun `kolom yang tak ditimpa tenant mewarisi platform seutuhnya`() {
        val resolver = resolver(
            platform = platformEmailSettings(
                fromAddress = "no-reply@duluin.net",
                branding = platformBranding,
                publicBaseUrl = "https://app.duluin.net",
            ),
            tenant = tenantEmailSettings(tenantId),
        )

        val identity = resolver.forTenant(tenantId)

        assertThat(identity.fromAddress).isEqualTo("no-reply@duluin.net")
        assertThat(identity.replyTo).isNull()
        assertThat(identity.branding).isEqualTo(platformBranding)
        assertThat(identity.logoUrl).isEqualTo("https://app.duluin.net/api/public/email-logo")
    }

    @Test
    fun `tanpa timpaan nama, surat berangkat atas nama ISP bukan nama platform`() {
        // Tingkat tengah rantai nama. Tanpanya pelanggan menerima tagihan internetnya dari
        // nama yang tak pernah ia kenal — lebih mirip penipuan daripada pemberitahuan.
        val resolver = resolver(platform = platformEmailSettings(fromName = "NetOps Console"))

        assertThat(resolver.forTenant(tenantId).fromName).isEqualTo(DEMO_TENANT_NAME)
    }

    @Test
    fun `tenant yang tak dikenal jatuh ke nama platform`() {
        val resolver = resolver(
            platform = platformEmailSettings(fromName = "NetOps Console"),
            tenants = FakeTenantApi(null),
        )

        assertThat(resolver.forTenant(tenantId).fromName).isEqualTo("NetOps Console")
    }

    @Test
    fun `jalur platform tak menyentuh baris tenant sama sekali`() {
        // Peringatan job terbit dari utas penjaga tanpa konteks tenant; satu query ber-RLS
        // di sana meledak. Repo yang meledak di bawah ini menegakkan janji itu.
        val resolver = EmailBrandingResolver(
            FakePlatformEmailSettingsRepository(platformEmailSettings(fromName = "NetOps Console")),
            ExplodingTenantRepo(),
            FakeTenantApi(tenantId),
            MailProperties(),
        )

        val identity = resolver.platformOnly()

        assertThat(identity.fromName).isEqualTo("NetOps Console")
        assertThat(identity.replyTo).isNull()
    }

    @Test
    fun `platform yang belum menyetel apa pun tetap menghasilkan identitas yang sah`() {
        val identity = brandingResolver(tenants = FakeTenantApi(tenantId)).forTenant(tenantId)

        assertThat(identity.fromAddress).isNull()
        assertThat(identity.fromName).isEqualTo(DEMO_TENANT_NAME)
        assertThat(identity.branding).isEqualTo(EmailBranding.EMPTY)
        assertThat(identity.logoUrl).isNull()
    }

    @Test
    fun `URL publik dari env dipakai saat baris DB belum mengisinya`() {
        val resolver = resolver(
            platform = platformEmailSettings(branding = platformBranding, publicBaseUrl = null),
            mailProperties = MailProperties(publicBaseUrl = "https://env.duluin.net/"),
        )

        // Garis miring di ekor dipangkas supaya tak lahir URL berpenggal ganda.
        assertThat(resolver.forTenant(tenantId).logoUrl).isEqualTo("https://env.duluin.net/api/public/email-logo")
    }

    @Test
    fun `tanpa URL publik email tetap berangkat, hanya tanpa logo`() {
        val resolver = resolver(platform = platformEmailSettings(branding = platformBranding))

        assertThat(resolver.forTenant(tenantId).logoUrl).isNull()
    }

    @Test
    fun `URL publik terisi tapi tak ada logo tetap null`() {
        val resolver = resolver(platform = platformEmailSettings(publicBaseUrl = "https://app.duluin.net"))

        assertThat(resolver.forTenant(tenantId).logoUrl).isNull()
    }

    private fun resolver(
        platform: PlatformEmailSettings? = null,
        tenant: TenantEmailSettings? = null,
        tenants: FakeTenantApi = FakeTenantApi(tenantId),
        mailProperties: MailProperties = MailProperties(),
    ) = brandingResolver(
        platform = FakePlatformEmailSettingsRepository(platform),
        tenant = FakeTenantEmailSettingsRepository(tenant),
        tenants = tenants,
        mailProperties = mailProperties,
    )

    /** Meledak begitu disentuh — dipakai untuk membuktikan jalur platform tak membacanya. */
    private class ExplodingTenantRepo : TenantEmailSettingsRepository {
        override fun find(): TenantEmailSettings =
            throw AssertionError("jalur platform tak boleh membaca setelan tenant")

        override fun save(settings: TenantEmailSettings): TenantEmailSettings =
            throw AssertionError("jalur platform tak boleh menulis setelan tenant")
    }
}
