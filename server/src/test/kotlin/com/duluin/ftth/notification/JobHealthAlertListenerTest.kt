package com.duluin.ftth.notification

import com.duluin.ftth.common.infrastructure.config.ObservabilityProperties
import com.duluin.ftth.common.infrastructure.observability.JobHealth
import com.duluin.ftth.common.infrastructure.observability.ScheduledJobRecovered
import com.duluin.ftth.common.infrastructure.observability.ScheduledJobStalled
import com.duluin.ftth.notification.application.port.outbound.EmailDispatcher
import com.duluin.ftth.notification.application.service.EmailRenderer
import com.duluin.ftth.notification.application.service.JobHealthAlertListener
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Isi email peringatan job. Yang diuji bukan sekadar "terkirim", melainkan bahwa surat itu
 * memuat keterangan yang membuat penerimanya tahu harus melihat ke mana — nama job, modul,
 * kapan terakhir berhasil, dan galat terakhirnya. Peringatan tanpa keterangan hanya
 * memindahkan kepanikan, tak memperpendek waktu perbaikan.
 *
 * Keterangan itu dibaca dari bagian TEKS POLOS surat: tabel diagnostik di sini bersandar
 * pada perataan spasi, dan bagian teks polos-lah yang menyimpannya apa adanya.
 */
class JobHealthAlertListenerTest {

    private val job = JobHealth(
        name = "BillingScheduler.issueInvoices",
        module = "billing",
        interval = Duration.ofHours(12),
        runs = 41,
        failures = 3,
        lastStartedAt = Instant.parse("2026-08-10T01:00:00Z"),
        lastSuccessAt = Instant.parse("2026-08-09T13:00:00Z"),
        lastFailureAt = Instant.parse("2026-08-10T01:00:30Z"),
        lastError = "SQLTransientConnectionException: connection is not available",
        lastDuration = Duration.ofSeconds(30),
        running = false,
        sinceSuccess = Duration.ofHours(38).plusMinutes(15),
        stallAfter = Duration.ofHours(36),
        stalled = true,
    )

    /**
     * Merek yang dipakai selalu merek platform — [brandingResolver] bawaannya memang tak
     * menyentuh repo tenant, persis seperti utas penjaga yang berjalan tanpa konteks tenant.
     */
    private fun listener(dispatcher: EmailDispatcher, to: String = "ops@duluin.com") =
        JobHealthAlertListener(dispatcher, brandingResolver(), EmailRenderer(), ObservabilityProperties(alertEmail = to))

    @Test
    fun `email macet memuat identitas job, umur sukses terakhir, dan galatnya`() {
        val dispatcher = RecordingEmailDispatcher()

        listener(dispatcher).on(ScheduledJobStalled(job, repeated = false))

        val message = dispatcher.messages.single()
        assertThat(message.to).isEqualTo("ops@duluin.com")
        assertThat(message.subject).contains("MACET").contains("BillingScheduler.issueInvoices")
        assertThat(message.textBody)
            .contains("BillingScheduler.issueInvoices (modul billing)")
            .contains("tiap 12 jam")
            .contains("2026-08-09T13:00:00Z")
            // Dua satuan terbesar saja — "PT38H15M" tak terbaca oleh orang yang baru bangun.
            .contains("1 hari 14 jam")
            .contains("41 jalan, 3 gagal")
            .contains("connection is not available")
        // Bagian HTML ikut berangkat supaya surat tak dicurigai filter spam sebagai HTML kosong.
        assertThat(message.htmlBody).contains("BillingScheduler.issueInvoices")
    }

    @Test
    fun `pengingat berkala dibedakan dari kabar pertama`() {
        val dispatcher = RecordingEmailDispatcher()

        listener(dispatcher).on(ScheduledJobStalled(job, repeated = true))

        val message = dispatcher.messages.single()
        assertThat(message.subject).contains("MASIH MACET")
        assertThat(message.textBody).contains("masih berlangsung")
    }

    @Test
    fun `job yang berhenti dijadwalkan tanpa galat tetap dijelaskan`() {
        val dispatcher = RecordingEmailDispatcher()
        val silent = job.copy(lastError = null, lastSuccessAt = null, runs = 0, failures = 0)

        listener(dispatcher).on(ScheduledJobStalled(silent, repeated = false))

        assertThat(dispatcher.messages.single().textBody)
            .contains("belum pernah sejak server hidup")
            .contains("berhenti dijadwalkan")
    }

    @Test
    fun `kabar pemulihan dikirim dengan judul yang berbeda`() {
        val dispatcher = RecordingEmailDispatcher()

        listener(dispatcher).on(ScheduledJobRecovered(job.copy(stalled = false)))

        val message = dispatcher.messages.single()
        assertThat(message.subject).contains("Pulih")
        assertThat(message.textBody).contains("kembali berhasil")
    }

    @Test
    fun `tanpa alamat penerima tak ada yang dikirim`() {
        val dispatcher = RecordingEmailDispatcher()

        listener(dispatcher, to = "  ").on(ScheduledJobStalled(job, repeated = false))

        assertThat(dispatcher.messages).isEmpty()
    }

    @Test
    fun `SMTP yang meledak tidak menjatuhkan penjaga`() {
        val dispatcher = RecordingEmailDispatcher(failure = IllegalStateException("mail server down"))

        // Utas penjaga yang mati gara-gara SMTP mati berarti kehilangan pemantauan justru
        // pada saat infrastrukturnya sedang bermasalah — persis ketika ia paling dibutuhkan.
        assertThatCode { listener(dispatcher).on(ScheduledJobStalled(job, repeated = false)) }
            .doesNotThrowAnyException()
    }
}
