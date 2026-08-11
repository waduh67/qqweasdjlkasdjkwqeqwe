package com.duluin.ftth.notification.adapter.outbound.messaging

import com.duluin.ftth.notification.application.port.outbound.PlatformEmailSettingsRepository
import com.duluin.ftth.notification.domain.model.SmtpTransport
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.stereotype.Component

/**
 * Menyediakan [JavaMailSender] yang berlaku SEKARANG, dengan tiga tingkat:
 *
 *  1. baris `platform_email_setting` yang host-nya terisi → bangun sender dari situ;
 *  2. bila kosong → bean bawaan Spring Boot dari `spring.mail.*` (deploy lama tetap jalan);
 *  3. bila dua-duanya kosong → null, dan dispatcher jatuh ke mode catat-ke-log.
 *
 * Pabrik ini ada karena autokonfigurasi Boot tak cukup lagi: bean-nya hanya dibuat bila
 * `spring.mail.host` terisi, dan nilainya BEKU sepanjang umur proses. Setelan yang bisa
 * diubah dari layar admin menuntut sender yang bisa dibangun ulang tanpa restart.
 *
 * Sender di-cache berdasar sidik jari konfigurasi, bukan disimpan begitu saja: membangun
 * `JavaMailSenderImpl` tiap email itu boros, tapi cache tanpa sidik jari berarti setelan
 * yang baru disimpan tak pernah berlaku sampai container di-restart — persis masalah yang
 * hendak dihapus fitur ini.
 *
 * Membaca port keluar dari dalam adapter memang tak lazim; ditempuh di sini karena
 * sambungan SMTP adalah detail transport murni yang tak punya urusan dengan use case mana
 * pun, sehingga menyeretnya lewat lapisan application hanya menambah perantara kosong.
 */
@Component
class SmtpSenderFactory(
    private val settingsRepo: PlatformEmailSettingsRepository,
    private val fallbackSender: ObjectProvider<JavaMailSender>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cache: Cached? = null

    /** Sender yang berlaku, atau null bila tak ada SMTP terkonfigurasi di mana pun. */
    fun current(): JavaMailSender? {
        val transport = runCatching { settingsRepo.find()?.resolveSmtp() }
            .onFailure { log.warn("Setelan SMTP platform tak terbaca; memakai setelan env", it) }
            .getOrNull()
            ?: return envSender()
        val fingerprint = transport.fingerprint()
        cache?.takeIf { it.fingerprint == fingerprint }?.let { return it.sender }
        val sender = build(transport)
        cache = Cached(fingerprint, sender)
        return sender
    }

    /**
     * Bean bawaan Boot — tapi hanya bila host-nya sungguh terisi.
     *
     * `spring.mail.host` di `application.yml` SELALU hadir (`${FTTH_MAIL_HOST:}`), cuma
     * nilainya kosong bila env tak menyetelnya, dan Boot tetap membuat beannya. Tanpa
     * saringan ini tingkat ketiga tak pernah tercapai: dev yang belum menyetel SMTP di mana
     * pun menerima "Mail server host not specified" alih-alih email yang tercatat di log.
     */
    private fun envSender(): JavaMailSender? =
        fallbackSender.getIfAvailable()?.takeUnless { it is JavaMailSenderImpl && it.host.isNullOrBlank() }

    private fun build(transport: SmtpTransport): JavaMailSender = JavaMailSenderImpl().apply {
        host = transport.host
        port = transport.port
        transport.username?.let { username = it }
        transport.password?.let { password = it }
        defaultEncoding = Charsets.UTF_8.name()
        javaMailProperties.apply {
            this["mail.smtp.auth"] = transport.auth.toString()
            this["mail.smtp.starttls.enable"] = transport.startTls.toString()
            // Timeout eksplisit: tanpa ini utas kirim bisa menggantung sampai TCP menyerah
            // sendiri (menit-menit), dan jalur kirim kita berjalan di dalam transaksi.
            this["mail.smtp.connectiontimeout"] = TIMEOUT_MS.toString()
            this["mail.smtp.timeout"] = TIMEOUT_MS.toString()
            this["mail.smtp.writetimeout"] = TIMEOUT_MS.toString()
        }
    }

    /**
     * Sidik jari konfigurasi. Password ikut serta lewat panjang & hash-nya saja — cukup untuk
     * mendeteksi perubahan, tanpa menaruh rahasia di memori lebih lama dari perlunya.
     */
    private fun SmtpTransport.fingerprint(): String =
        "$host|$port|${username.orEmpty()}|${password?.hashCode() ?: 0}|$auth|$startTls"

    private data class Cached(val fingerprint: String, val sender: JavaMailSender)

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
