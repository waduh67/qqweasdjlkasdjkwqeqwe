package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.iam.TenantAdminProvisionedEvent
import com.duluin.ftth.notification.application.service.EmailRenderer
import com.duluin.ftth.notification.application.service.EmailSubjectResolver
import com.duluin.ftth.notification.application.service.TenantWelcomeEmailListener
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Email selamat datang pendaftaran ISP. Tiga hal yang dijaga, semuanya konsekuensi dari kode
 * ISP yang kini dipilih server: kodenya HARUS terbaca di email (tak ada tempat lain yang
 * bertahan setelah tab pendaftaran ditutup), suratnya berangkat atas nama PLATFORM (tenant
 * penerimanya belum punya merek apa pun pada detik itu), dan relay yang mati tak boleh
 * merambat keluar — pendaftarannya sudah commit, membatalkannya sekarang cuma merusak.
 */
class TenantWelcomeEmailListenerTest {

    private val tenantId: UUID = UuidV7.generate()
    private val event = TenantAdminProvisionedEvent(
        tenantId = tenantId,
        tenantSlug = "pt-net-media-jaya",
        tenantName = "PT Net Media Jaya",
        adminEmail = "budi@netmedia.id",
        adminName = "Budi",
    )

    @Test
    fun `email memuat kode ISP, nama admin, dan email admin`() {
        val dispatcher = RecordingEmailDispatcher()

        listener(dispatcher).on(event)

        val message = dispatcher.messages.single()
        assertThat(message.to).isEqualTo("budi@netmedia.id")
        assertThat(message.textBody).contains("pt-net-media-jaya")
        assertThat(message.textBody).contains("Budi")
        assertThat(message.textBody).contains("budi@netmedia.id")
        // Bagian HTML membawa isi yang sama — klien yang menolak teks polos tak boleh kehilangan kodenya.
        assertThat(message.htmlBody).contains("pt-net-media-jaya")
    }

    @Test
    fun `subjek memakai jalur platform dengan nama ISP tersisip`() {
        val dispatcher = RecordingEmailDispatcher()

        listener(dispatcher, platformSubjects = mapOf(NotificationTrigger.TENANT_SIGNED_UP to "Selamat datang, {isp}"))
            .on(event)

        assertThat(dispatcher.subjects).containsExactly("Selamat datang, PT Net Media Jaya")
    }

    @Test
    fun `timpaan tenant atas subjek pendaftaran tak berpengaruh`() {
        // Tenant yang baru lahir tak mungkin punya timpaan, tapi tenant LAIN bisa — dan jalur
        // platform memang tak boleh melirik peta tenant sama sekali.
        val dispatcher = RecordingEmailDispatcher()

        listener(
            dispatcher,
            platformSubjects = mapOf(NotificationTrigger.TENANT_SIGNED_UP to "Selamat datang, {isp}"),
            tenantSubjects = mapOf(NotificationTrigger.TENANT_SIGNED_UP to "Halo dari tenant lain"),
        ).on(event)

        assertThat(dispatcher.subjects).containsExactly("Selamat datang, PT Net Media Jaya")
    }

    @Test
    fun `pengirim memakai identitas platform, bukan merek tenant`() {
        val dispatcher = RecordingEmailDispatcher()
        val platform = FakePlatformEmailSettingsRepository(
            platformEmailSettings(fromAddress = "halo@netops.id", fromName = "NetOps"),
        )
        // Tenant menimpa merek — dan timpaannya harus diabaikan surat ini.
        val tenant = FakeTenantEmailSettingsRepository(
            tenantEmailSettings(tenantId, replyToAddress = "cs@netmedia.id", fromName = "Net Media"),
        )

        listener(dispatcher, platform = platform, tenant = tenant).on(event)

        val message = dispatcher.messages.single()
        assertThat(message.fromAddress).isEqualTo("halo@netops.id")
        assertThat(message.fromName).isEqualTo("NetOps")
    }

    @Test
    fun `relay yang meledak tak merambat keluar listener`() {
        val dispatcher = RecordingEmailDispatcher(failure = IllegalStateException("SMTP mati"))

        assertThatCode { listener(dispatcher).on(event) }.doesNotThrowAnyException()
    }

    private fun listener(
        dispatcher: RecordingEmailDispatcher,
        platform: FakePlatformEmailSettingsRepository = FakePlatformEmailSettingsRepository(),
        tenant: FakeTenantEmailSettingsRepository = FakeTenantEmailSettingsRepository(),
        platformSubjects: Map<NotificationTrigger, String> = emptyMap(),
        tenantSubjects: Map<NotificationTrigger, String> = emptyMap(),
    ) = TenantWelcomeEmailListener(
        branding = brandingResolver(platform = platform, tenant = tenant, tenants = FakeTenantApi(tenantId)),
        subjects = EmailSubjectResolver(
            FakeEmailSubjectRepository(platformSubjects, tenantSubjects),
            FakeTenantApi(tenantId),
        ),
        renderer = EmailRenderer(),
        dispatcher = dispatcher,
    )
}
