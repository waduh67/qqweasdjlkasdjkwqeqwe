package com.duluin.ftth.notification.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Identitas pengirim email platform. Sambungan SMTP-nya sendiri (host, port, kredensial,
 * STARTTLS) dikonfigurasi lewat `spring.mail.*` bawaan Spring Boot — sengaja tak diduplikasi
 * di sini supaya hanya ada satu tempat menyetelnya.
 *
 * `spring.mail.host` kosong = tak ada bean pengirim sama sekali (autokonfigurasi Boot memang
 * bersyarat itu), dan adapter jatuh ke mode catat-ke-log. Itu keadaan bawaan pengembangan:
 * pemulihan password tetap bisa dicoba tanpa server SMTP, kodenya muncul di log.
 */
@ConfigurationProperties(prefix = "ftth.mail")
data class MailProperties(
    /** Alamat `From`. Kosong = kanal email dianggap belum siap dan tak akan dipakai. */
    val from: String = "",
    /** Nama tampilan pengirim; dipadukan jadi `Nama <alamat>`. */
    val fromName: String = "NetOps Console",
)
