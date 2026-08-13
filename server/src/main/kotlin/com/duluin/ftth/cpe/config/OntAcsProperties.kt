package com.duluin.ftth.cpe.config

import java.time.Duration

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Setelan TR-069 yang harus DIKETIK ORANG ke halaman ACS di ONT pelanggan — bukan
 * kredensial yang dipakai aplikasi.
 *
 * Bedakan dari [com.duluin.ftth.cpe.adapter.outbound.acs.GenieAcsProperties]: yang itu
 * alamat **NBI** (`http://genieacs-nbi:7557`) yang dipakai server untuk memerintah ACS —
 * internal, tanpa otentikasi, dan tak boleh pernah terlihat siapa pun. Yang di sini adalah
 * alamat **CWMP** (`:7547`) yang DIHUBUNGI PERANGKAT, plus kredensial yang diketik operator
 * ke ONT-nya. Aplikasi tak bisa menurunkannya sendiri dari NBI: hanya deployer yang tahu
 * alamat publik VPS-nya.
 *
 * [publicHost] KOSONG = UI menandai "belum dikonfigurasi" alih-alih menampilkan URL palsu,
 * cermin perilaku [com.duluin.ftth.bng.config.RadiusProperties.publicHost].
 */
@ConfigurationProperties(prefix = "ftth.cpe.ont")
data class OntAcsProperties(
    /** IP/host publik VPS yang ONT tuju untuk CWMP. Kosong → kartu setelan menandai belum siap. */
    val publicHost: String = "",
    /** Port CWMP GenieACS (yang dipublikasikan, bukan NBI). */
    val cwmpPort: Int = 7547,
    /** Username yang diketik ke kolom "ACS Username" di ONT; kosong = tanpa auth. */
    val acsUsername: String = "",
    val acsPassword: String = "",
    /** Kredensial Connection Request — yang dipakai ACS untuk MENGHUBUNGI BALIK perangkat. */
    val connectionRequestUsername: String = "",
    val connectionRequestPassword: String = "",
    /**
     * Nilai "Periodic Inform Interval" yang harus diketik di ONT. Bawaan pabrik ONT umumnya
     * 3600 detik — terlalu lama: perangkat baru dianggap offline sampai satu jam, dan aksi
     * on-demand yang gagal connection-request menunggu sejam untuk diantre. 300 detik cukup
     * pendek agar status tetap hidup tanpa membanjiri ACS.
     */
    val periodicInformInterval: Duration = Duration.ofSeconds(300),
)
