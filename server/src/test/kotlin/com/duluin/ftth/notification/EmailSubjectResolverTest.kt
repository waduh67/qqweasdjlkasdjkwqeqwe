package com.duluin.ftth.notification

import com.duluin.ftth.notification.application.service.EmailSubjectResolver
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tiga tingkat pewarisan baris subjek: timpaan TENANT → timpaan PLATFORM → konstanta kode.
 *
 * Tingkat ketiga yang paling mudah terlupa dan paling mahal bila salah: pemicu baru yang
 * belum punya baris di DB mana pun tetap harus punya subjek, karena email tanpa subjek
 * praktis pasti mendarat di folder spam.
 */
class EmailSubjectResolverTest {

    private val trigger = NotificationTrigger.INVOICE_DUE_SOON

    private fun resolver(
        platform: Map<NotificationTrigger, String> = emptyMap(),
        tenant: Map<NotificationTrigger, String> = emptyMap(),
    ) = EmailSubjectResolver(FakeEmailSubjectRepository(platform, tenant))

    @Test
    fun `timpaan tenant menang atas timpaan platform`() {
        val subject = resolver(
            platform = mapOf(trigger to "Tagihan dari penyedia layanan"),
            tenant = mapOf(trigger to "Tagihan Sinar Jaya Net"),
        ).forCurrentTenant(trigger)

        assertThat(subject).isEqualTo("Tagihan Sinar Jaya Net")
    }

    @Test
    fun `tanpa timpaan tenant, subjek platform yang dipakai`() {
        val subject = resolver(platform = mapOf(trigger to "Tagihan dari penyedia layanan"))
            .forCurrentTenant(trigger)

        assertThat(subject).isEqualTo("Tagihan dari penyedia layanan")
    }

    @Test
    fun `tanpa timpaan di mana pun, konstanta kode yang berlaku`() {
        assertThat(resolver().forCurrentTenant(trigger))
            .isEqualTo("Tagihan internet Anda akan jatuh tempo")
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
    fun `peta lengkap tenant memuat seluruh pemicu dengan tingkat yang benar`() {
        val effective = resolver(
            platform = mapOf(
                trigger to "Tagihan dari penyedia layanan",
                NotificationTrigger.INCIDENT_OPENED to "Ada gangguan di area Anda",
            ),
            tenant = mapOf(trigger to "Tagihan Sinar Jaya Net"),
        ).effectiveForCurrentTenant()

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
