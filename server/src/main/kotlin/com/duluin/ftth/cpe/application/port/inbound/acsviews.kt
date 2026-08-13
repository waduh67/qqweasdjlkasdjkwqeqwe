package com.duluin.ftth.cpe.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Bentuk baca KONSOL ACS — pandangan se-armada milik satu tenant, terpisah dari
 * `views.kt` yang melayani panel per-pelanggan di detail pelanggan.
 *
 * Dipisah berkas karena penontonnya berbeda: yang di sini dirakit dari tiga module
 * (cpe + monitoring + bng) dan sebagian dibaca teknisi yang tak boleh melihat daftar
 * perangkat sama sekali.
 */

/**
 * Alamat & kredensial yang DIKETIK ORANG ke halaman TR-069 di ONT pelanggan.
 *
 * Semuanya nilai global dari env deploy — tak ada data tenant di sini sama sekali,
 * itulah sebabnya izinnya cukup `cpe.acs.view` dan teknisi boleh melihatnya.
 *
 * [nbiBaseUrl] adalah pengungkapan yang DISENGAJA dan terkurasi — alamat internal
 * (`http://genieacs-nbi:7557`) yang tak routable dari luar jaringan Docker, ditampilkan
 * sebagai keterangan diagnostik di kartu informasi server. Yang tetap dilarang keras
 * adalah membocorkannya lewat jalur TAK terkurasi: pesan exception `RestClient` menyisipkan
 * URI penuh, jadi [AcsHealthView.message] hanya boleh berisi kalimat tetap + nama kelas
 * exception, tak pernah `message` aslinya.
 *
 * [configured] = `false` saat `FTTH_CPE_PUBLIC_HOST` belum diisi; UI menampilkan
 * peringatan alih-alih URL palsu, cermin `RadiusEndpointView.configured`.
 *
 * Kata sandi di sini memang dikirim ke browser siapa pun yang punya `cpe.acs.view`
 * (termasuk teknisi): nilainya sama untuk SEMUA ONT dan memang harus diketik teknisi
 * di lapangan. Ia tetap dilarang keras masuk ekspor CSV dan baris log mana pun.
 */
data class AcsServerInfoView(
    /** Alamat NBI yang dipakai APLIKASI (internal); keterangan diagnostik, bukan yang diketik ke ONT. */
    val nbiBaseUrl: String,
    /** URL CWMP yang diketik ke ONT, mis. `http://203.0.113.9:7547`. `null` bila belum disetel. */
    val cwmpUrl: String?,
    val acsUsername: String?,
    val acsPassword: String?,
    val connectionRequestUsername: String?,
    val connectionRequestPassword: String?,
    /** Selalu `true` — ONT wajib inform berkala agar status di konsol hidup. */
    val periodicInformEnabled: Boolean,
    /** Bawaan pabrik ONT umumnya 3600; nilai ini yang benar. */
    val periodicInformIntervalSeconds: Long,
    /** Selang sinkronisasi proyeksi dari ACS — menjelaskan seberapa lawas tabel device. */
    val syncIntervalSeconds: Long,
    val configured: Boolean,
)

/**
 * Hasil probe kesehatan ACS. [message] adalah kalimat berbahasa Indonesia yang tetap;
 * pesan exception asli hanya masuk log server karena memuat URI NBI.
 */
data class AcsHealthView(
    /** ONLINE atau OFFLINE. */
    val status: String,
    val latencyMs: Long?,
    val checkedAt: Instant,
    val message: String,
)

/**
 * Ringkasan armada tenant. Semua angka berasal dari tabel `cpe_device` yang ber-RLS,
 * TIDAK PERNAH dari hitungan NBI — hitungan NBI mencakup semua tenant dan akan
 * membocorkan skala platform.
 *
 * "Online" di sini berarti *melapor ke ACS dalam ambang basi*, bukan *ONU hidup di
 * OLT*: ONT menyala yang klien CWMP-nya macet akan terhitung offline di sini tapi
 * online di `/monitoring`.
 *
 * [signalSampleCount] dikembalikan bersama [totalDevices] supaya UI bisa memajang
 * penyebutnya — `-19,8 dBm` telanjang dari 12 bacaan pada 300 ONU terbaca sebagai
 * kesehatan seluruh armada, padahal bukan.
 */
data class AcsStatsView(
    val totalDevices: Int,
    val onlineDevices: Int,
    val offlineDevices: Int,
    /** Rata-rata aritmetik RX saja; TX tak pernah ikut dirata-ratakan (besaran berbeda). */
    val avgRxPowerDbm: Double?,
    val signalSampleCount: Int,
    val lastSyncAt: Instant?,
    val lastSyncOk: Boolean?,
)

/**
 * Satu baris tabel device di konsol. Tiap kolom datang dari sumber terbaiknya:
 * identitas & SSID & suhu dari sinkronisasi ACS, RX/TX dari metrik optik OLT
 * (module monitoring), PPPoE dari module bng, nama pelanggan dari module customer.
 */
data class AcsDeviceRowView(
    val id: UUID,
    val serialNumber: String,
    val customerId: UUID?,
    val customerName: String?,
    val manufacturer: String?,
    val model: String?,
    val softwareVersion: String?,
    val online: Boolean,
    val lastInformAt: Instant?,
    val ipAddress: String?,
    val ssid: String?,
    val pppoeUsername: String?,
    val pppoeOnline: Boolean?,
    val rxPowerDbm: Double?,
    val txPowerDbm: Double?,
    /** Parameter vendor; `null` di hampir semua armada sampai path suhunya dikonfigurasi. */
    val temperatureC: Double?,
)

/** Satu baris jejak aksi lintas device untuk jendela "View Logs". */
data class AcsActivityView(
    val id: UUID,
    val deviceId: UUID,
    val serialNumber: String?,
    val customerName: String?,
    val action: String,
    val status: String,
    val detail: String?,
    val requestedByEmail: String?,
    val requestedAt: Instant,
)

/**
 * Hasil "Segarkan Batch". Ini SAPUAN BERPLAFON, bukan "semua": [candidates] adalah
 * perangkat online yang memenuhi syarat, [attempted] yang benar-benar disentuh sebelum
 * plafon atau anggaran waktu habis, dan [skipped] selisihnya — dikembalikan jujur agar
 * operator tahu fiturnya berplafon, bukan rusak.
 */
data class AcsBulkRefreshView(
    val candidates: Int,
    val attempted: Int,
    /** Perangkat yang ACS berhasil hubungi seketika ("ACS Connect"). */
    val connected: Int,
    /** Perintah diterima NBI tapi perangkat tak menjawab — diantre untuk inform berikutnya. */
    val queued: Int,
    val failed: Int,
    val skipped: Int,
    val message: String,
)
