package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.service.EmailSubjectResolver
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Tiga tingkat pewarisan baris subjek: timpaan TENANT → timpaan PLATFORM → konstanta kode,
 * plus dua pengecualian yang menempel padanya: pemicu [EmailSubjectResolver.PLATFORM_ONLY]
 * yang melewati tingkat tenant, dan token `{isp}`.
 *
 * Tingkat ketiga yang paling mudah terlupa dan paling mahal bila salah: pemicu baru yang
 * belum punya baris di DB mana pun tetap harus punya subjek, karena email tanpa subjek
 * praktis pasti mendarat di folder spam.
 */
class EmailSubjectResolverTest {

    private val trigger = NotificationTrigger.INVOICE_DUE_SOON
    private val tenantId: UUID = UuidV7.generate()

    private fun resolver(
        platform: Map<NotificationTrigger, String> = emptyMap(),
        tenant: Map<NotificationTrigger, String> = emptyMap(),
        ispName: String = "Sinar Jaya Net",
    ) = EmailSubjectResolver(FakeEmailSubjectRepository(platform, tenant), FakeTenantApi(tenantId, ispName))

    /** Semua jalur `forCurrentTenant` butuh tenant aktif — RLS-lah yang menyaring timpaannya. */
    private fun <T> asTenant(block: () -> T): T = TenantContext.runAs(tenantId) { block() }

    @Test
    fun `timpaan tenant menang atas timpaan platform`() {
        val subject = asTenant {
            resolver(
                platform = mapOf(trigger to "Tagihan dari penyedia layanan"),
                tenant = mapOf(trigger to "Tagihan Sinar Jaya Net"),
            ).forCurrentTenant(trigger)
        }

        assertThat(subject).isEqualTo("Tagihan Sinar Jaya Net")
    }

    @Test
    fun `tanpa timpaan tenant, subjek platform yang dipakai`() {
        val subject = asTenant {
            resolver(platform = mapOf(trigger to "Tagihan dari penyedia layanan")).forCurrentTenant(trigger)
        }

        assertThat(subject).isEqualTo("Tagihan dari penyedia layanan")
    }

    @Test
    fun `tanpa timpaan di mana pun, konstanta kode yang berlaku`() {
        assertThat(asTenant { resolver().forCurrentTenant(trigger) })
            .isEqualTo("Tagihan internet Anda akan jatuh tempo")
    }

    @Test
    fun `token isp diganti nama tenant saat dikirim`() {
        val subject = asTenant {
            resolver(platform = mapOf(trigger to "Tagihan {isp} sudah terbit")).forCurrentTenant(trigger)
        }

        assertThat(subject).isEqualTo("Tagihan Sinar Jaya Net sudah terbit")
    }

    @Test
    fun `timpaan tenant untuk pemicu khusus platform diabaikan`() {
        // Subjek pemulihan password adalah bagian dari jalan masuk pelanggan. ISP yang
        // menimpanya bisa membuat email itu tak lagi terbaca sebagai email keamanan — jadi
        // barisnya diabaikan di sini, bukan cuma disembunyikan dari layar tenant.
        val reset = NotificationTrigger.PORTAL_PASSWORD_RESET
        val resolver = resolver(
            platform = mapOf(reset to "Kode masuk {isp}"),
            tenant = mapOf(reset to "Halo dari kami"),
        )

        assertThat(asTenant { resolver.forCurrentTenant(reset) }).isEqualTo("Kode masuk Sinar Jaya Net")
        assertThat(asTenant { resolver.effectiveForCurrentTenant()[reset] }).isEqualTo("Kode masuk {isp}")
    }

    @Test
    fun `jalur platform tak menimbang timpaan tenant`() {
        // Layar setelan platform harus menampilkan apa yang berlaku BAGINYA, bukan apa yang
        // kebetulan ditimpa tenant yang sedang aktif di konteks.
        val resolver = resolver(
            platform = mapOf(trigger to "Tagihan dari penyedia layanan"),
            tenant = mapOf(trigger to "Tagihan Sinar Jaya Net"),
        )

        assertThat(resolver.forPlatform(trigger)).isEqualTo("Tagihan dari penyedia layanan")
    }

    @Test
    fun `jalur platform mengganti token isp dengan nama yang dioper`() {
        // Email selamat datang berangkat sebelum tenant-nya punya setelan apa pun, jadi nama
        // ISP-nya dioper langsung — bukan dibaca dari konteks yang memang belum ada.
        val subject = resolver().forPlatform(NotificationTrigger.TENANT_SIGNED_UP, "PT Net Media Jaya")

        assertThat(subject).isEqualTo("Pendaftaran PT Net Media Jaya berhasil — kode ISP Anda")
    }

    @Test
    fun `peta lengkap tenant memuat seluruh pemicu dengan tingkat yang benar`() {
        val effective = asTenant {
            resolver(
                platform = mapOf(
                    trigger to "Tagihan dari penyedia layanan",
                    NotificationTrigger.INCIDENT_OPENED to "Ada gangguan di area Anda",
                ),
                tenant = mapOf(trigger to "Tagihan Sinar Jaya Net"),
            ).effectiveForCurrentTenant()
        }

        // Tak ada pemicu yang boleh hilang dari peta: kolomnya di layar setelan berjumlah tetap.
        assertThat(effective.keys).containsExactlyInAnyOrderElementsOf(NotificationTrigger.entries)
        assertThat(effective[trigger]).isEqualTo("Tagihan Sinar Jaya Net")
        assertThat(effective[NotificationTrigger.INCIDENT_OPENED]).isEqualTo("Ada gangguan di area Anda")
        assertThat(effective[NotificationTrigger.SUBSCRIPTION_ACTIVATED])
            .isEqualTo("Layanan internet Anda sudah aktif")
    }

    @Test
    fun `peta lengkap platform hanya menimpa bawaan kode`() {
        val effective = resolver(
            platform = mapOf(trigger to "Tagihan dari penyedia layanan"),
            tenant = mapOf(NotificationTrigger.INCIDENT_OPENED to "Gangguan Sinar Jaya"),
        ).effectiveForPlatform()

        assertThat(effective[trigger]).isEqualTo("Tagihan dari penyedia layanan")
        assertThat(effective[NotificationTrigger.INCIDENT_OPENED]).isEqualTo("Pemberitahuan gangguan layanan")
    }

    @Test
    fun `setiap pemicu punya subjek bawaan`() {
        // Menambah pemicu tanpa subjeknya harus gagal di sini, bukan diam-diam mengirim
        // email tanpa judul ke pelanggan sungguhan.
        assertThat(EmailSubjectResolver.DEFAULT_SUBJECTS.keys)
            .containsExactlyInAnyOrderElementsOf(NotificationTrigger.entries)
        assertThat(EmailSubjectResolver.DEFAULT_SUBJECTS.values).noneMatch { it.isBlank() }
    }
}
