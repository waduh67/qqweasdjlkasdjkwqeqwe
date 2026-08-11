package com.duluin.ftth.notification

import com.duluin.ftth.notification.adapter.outbound.messaging.SmtpSenderFactory
import com.duluin.ftth.notification.application.port.outbound.PlatformEmailSettingsRepository
import com.duluin.ftth.notification.domain.model.PlatformEmailSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl

/**
 * Tiga tingkat sumber sambungan SMTP: baris DB → `spring.mail.*` dari env → tak ada sama
 * sekali (mode catat-ke-log).
 *
 * Tingkat kedua yang paling penting dijaga: deploy yang sudah berjalan hanya punya env, dan
 * fitur setelan email tak boleh mematikan pengiriman mereka hanya karena tabelnya masih
 * kosong. Cache-nya ikut diuji karena tanpa sidik jari konfigurasi, setelan yang baru
 * disimpan tak pernah berlaku sampai container di-restart — persis masalah yang hendak
 * dihapus fitur ini.
 */
class SmtpSenderFactoryTest {

    private val envSender = JavaMailSenderImpl().apply { host = "smtp.env.local" }

    @Test
    fun `baris DB yang terisi menang atas setelan env`() {
        val factory = factory(platformEmailSettings(smtpHost = "smtp.db.local", smtpPort = 2525))

        val sender = factory.current() as JavaMailSenderImpl

        assertThat(sender.host).isEqualTo("smtp.db.local")
        assertThat(sender.port).isEqualTo(2525)
        assertThat(sender).isNotSameAs(envSender)
    }

    @Test
    fun `kredensial dan saklar transport ikut terpasang`() {
        val factory = factory(
            platformEmailSettings(
                smtpHost = "smtp.db.local",
                smtpUsername = "postmaster@duluin.net",
                smtpPassword = "rahasia",
                smtpAuth = true,
                smtpStartTls = false,
            ),
        )

        val sender = factory.current() as JavaMailSenderImpl

        assertThat(sender.username).isEqualTo("postmaster@duluin.net")
        assertThat(sender.password).isEqualTo("rahasia")
        assertThat(sender.javaMailProperties["mail.smtp.auth"]).isEqualTo("true")
        assertThat(sender.javaMailProperties["mail.smtp.starttls.enable"]).isEqualTo("false")
        // Tanpa timeout eksplisit, utas kirim bisa menggantung menit-menit di dalam transaksi.
        assertThat(sender.javaMailProperties["mail.smtp.timeout"]).isEqualTo("10000")
    }

    @Test
    fun `host DB kosong jatuh ke sender bawaan Spring Boot`() {
        assertThat(factory(platformEmailSettings(smtpHost = "   ")).current()).isSameAs(envSender)
    }

    @Test
    fun `baris DB yang belum pernah ada jatuh ke sender bawaan`() {
        assertThat(factory(null).current()).isSameAs(envSender)
    }

    @Test
    fun `dua-duanya kosong berarti tak ada pengirim, bukan kegagalan`() {
        val factory = SmtpSenderFactory(FakePlatformEmailSettingsRepository(null), FakeSenderProvider(null))

        // Null = dispatcher jatuh ke mode catat-ke-log; itu keadaan bawaan pengembangan.
        assertThat(factory.current()).isNull()
    }

    @Test
    fun `sender env tanpa host dianggap tak ada, bukan relay yang pasti gagal`() {
        // `spring.mail.host` selalu HADIR di application.yml (`${FTTH_MAIL_HOST:}`), jadi Boot
        // tetap membuat beannya walau env tak menyetel apa pun. Tanpa saringan ini, deploy
        // yang belum punya SMTP di mana pun menerima "Mail server host not specified"
        // alih-alih mode catat-ke-log.
        val factory = SmtpSenderFactory(
            FakePlatformEmailSettingsRepository(null),
            FakeSenderProvider(JavaMailSenderImpl()),
        )

        assertThat(factory.current()).isNull()
    }

    @Test
    fun `setelan yang tak berubah memakai sender yang sama`() {
        val factory = factory(platformEmailSettings(smtpHost = "smtp.db.local"))

        assertThat(factory.current()).isSameAs(factory.current())
    }

    @Test
    fun `setelan yang berubah langsung berlaku tanpa restart`() {
        val repo = FakePlatformEmailSettingsRepository(platformEmailSettings(smtpHost = "smtp.lama.local"))
        val factory = SmtpSenderFactory(repo, FakeSenderProvider(envSender))
        val first = factory.current()

        repo.current = platformEmailSettings(smtpHost = "smtp.baru.local")
        val second = factory.current() as JavaMailSenderImpl

        assertThat(second).isNotSameAs(first)
        assertThat(second.host).isEqualTo("smtp.baru.local")
    }

    @Test
    fun `setelan yang tak terbaca tak menghentikan pengiriman`() {
        // Tabel yang belum termigrasi atau koneksi yang putus sesaat tak boleh membuat email
        // berhenti sama sekali selama env masih menyediakan relay.
        val factory = SmtpSenderFactory(ExplodingSettingsRepo(), FakeSenderProvider(envSender))

        assertThat(factory.current()).isSameAs(envSender)
    }

    private fun factory(settings: PlatformEmailSettings?) =
        SmtpSenderFactory(FakePlatformEmailSettingsRepository(settings), FakeSenderProvider(envSender))

    /** Cukup [getIfAvailable] yang dipakai pabrik; sisanya tak pernah tersentuh. */
    private class FakeSenderProvider(private val sender: JavaMailSender?) : ObjectProvider<JavaMailSender> {
        override fun getObject(): JavaMailSender = sender ?: throw UnsupportedOperationException("tak ada bean")
        override fun getIfAvailable(): JavaMailSender? = sender
    }

    private class ExplodingSettingsRepo : PlatformEmailSettingsRepository {
        override fun find(): PlatformEmailSettings = throw IllegalStateException("tabel belum ada")
        override fun save(settings: PlatformEmailSettings): PlatformEmailSettings =
            throw UnsupportedOperationException()
    }
}
