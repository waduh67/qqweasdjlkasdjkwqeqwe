package com.duluin.ftth.notification.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Identitas pengirim email — CADANGAN dari setelan platform di DB.
 *
 * Sejak platform admin bisa menyetel SMTP & identitas pengirim lewat layar
 * `/platform/email`, nilai di sini bukan lagi satu-satunya sumber: baris DB yang terisi
 * selalu menang, dan properti ini yang dipakai bila DB masih kosong. Dengan begitu deploy
 * lama yang hanya punya env tetap berjalan tanpa perubahan apa pun.
 *
 * Sambungan SMTP-nya sendiri (host, port, kredensial, STARTTLS) tetap lewat `spring.mail.*`
 * bawaan Spring Boot — sengaja tak diduplikasi di sini. `spring.mail.host` kosong DAN host
 * di DB kosong = tak ada pengirim sama sekali, dan adapter jatuh ke mode catat-ke-log. Itu
 * keadaan bawaan pengembangan: pemulihan password tetap bisa dicoba tanpa server SMTP,
 * kodenya muncul di log.
 */
@ConfigurationProperties(prefix = "ftth.mail")
data class MailProperties(
    /** Alamat `From`. Kosong = kanal email dianggap belum siap dan tak akan dipakai. */
    val from: String = "",
    /** Nama tampilan pengirim; dipadukan jadi `Nama <alamat>`. */
    val fromName: String = "NetOps Console",
    /**
     * URL absolut aplikasi (mis. `https://app.duluin.net`) untuk merangkai `<img src>` logo
     * di badan email — klien email tak mengerti path relatif. Cadangan dari
     * `platform_email_setting.public_base_url`; kosong di dua-duanya = email tetap terkirim,
     * hanya tanpa logo.
     */
    val publicBaseUrl: String = "",
)
