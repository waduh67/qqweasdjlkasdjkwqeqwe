package com.duluin.ftth.bng.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Proyeksi satu BRAS/NAS. [hasCoaSecret]/[hasApiSecret] menandai rahasianya sudah diisi
 * tanpa pernah membocorkan nilainya lewat API. Kredensial non-rahasia
 * ([apiUsername]/[apiPort]/[apiUseTls]) dibalikkan apa adanya agar form bisa memuat
 * nilainya kembali saat diedit.
 */
data class NasView(
    val id: UUID,
    val name: String,
    val vendor: String,
    val address: String?,
    val nasIdentifier: String?,
    val hasCoaSecret: Boolean,
    val collectorId: UUID?,
    val enabled: Boolean,
    val apiUsername: String?,
    val hasApiSecret: Boolean,
    val apiPort: Int?,
    val apiUseTls: Boolean,
    /** Area yang dinaungi BRAS ini — dipakai PSB untuk auto-pilih BRAS dari area pelanggan. */
    val areaIds: List<UUID>,
    /**
     * Rute kontrol sesi yang tersimpul dari alamat & collector BRAS ini (DIRECT/VPN/
     * COLLECTOR/NONE) — bukan isian, melainkan akibat. Ditampilkan supaya operator melihat
     * lebih dulu bahwa isolir & Reset Login ke BRAS ini takkan sampai (NONE), alih-alih
     * menemukannya dari pelanggan yang mestinya terputus tapi tetap online.
     */
    val reachability: String,
)

/**
 * Pratinjau satu baris `/ppp/secret` RouterOS untuk wizard bulk-import PPPoE. Password
 * SENGAJA tak disertakan — pratinjau hanya untuk operator memilih baris & memetakan
 * [profile]→paket; server-lah yang menarik ulang password saat commit impor (tak pernah
 * bocor ke browser). [comment] sering memuat nama/ID pelanggan, [disabled] menandai akun
 * yang dimatikan di router.
 */
data class PppSecretView(
    val name: String,
    val profile: String?,
    val service: String?,
    val comment: String?,
    val disabled: Boolean,
)

/**
 * Koordinat FreeRADIUS pusat yang tenant arahkan router-nya (nilai `address=`+port di
 * `/radius` Mikrotik). Sama untuk semua tenant — satu server RADIUS-as-a-service, bukan
 * per-tenant. [configured] false berarti platform belum mengisi [host] (env
 * `FTTH_RADIUS_PUBLIC_HOST`); UI menampilkan catatan alih-alih host tebakan. [coaPort]
 * adalah arah balik (SERVER → BRAS) untuk DAE/CoA, disertakan agar operator membuka port
 * itu di Mikrotik.
 */
data class RadiusEndpointView(
    val host: String?,
    val authPort: Int,
    val acctPort: Int,
    val coaPort: Int,
    val configured: Boolean,
    /**
     * Alamat RADIUS versi overlay, satu per blok tunnel VPN. Kosong bila platform tak
     * memakai VPN.
     */
    val vpnHosts: List<RadiusVpnHostView> = emptyList(),
    /**
     * Nama address-list yang RADIUS kirimkan (VSA `Mikrotik-Address-List`) untuk pelanggan
     * terisolir. Disodorkan ke UI karena router HARUS memakai nama yang sama persis di aturan
     * firewall-nya: salah satu huruf saja, address-list-nya terisi tapi tak ada aturan yang
     * membacanya — pelanggan "terisolir" yang internetnya justru lancar tanpa jejak kesalahan
     * di mana pun.
     */
    val isolirAddressList: String = "isolir",
)

/**
 * Untuk BRAS yang masuk lewat overlay VPN, [host] inilah yang ditulis di `/radius
 * address=` — BUKAN [RadiusEndpointView.host] yang publik.
 *
 * Sebabnya bukan selera: FreeRADIUS mengenali klien dari ALAMAT ASAL paketnya. Router
 * yang sudah ber-tunnel tapi diarahkan ke IP publik akan keluar lewat internet biasa,
 * jadi paketnya datang dari IP publik lokasi pelanggan — bukan alamat overlay yang
 * terdaftar — dan permintaan dari klien tak dikenal DIABAIKAN tanpa balasan. Di router
 * gejalanya cuma "timeout", tanpa satu pun petunjuk bahwa alamatnya yang keliru.
 *
 * [tunnelCidr] dipakai UI untuk mencocokkan: alamat BRAS yang jatuh di dalam blok ini
 * berarti BRAS-nya lewat tunnel, jadi skrip yang disodorkan memakai [host] ini.
 */
data class RadiusVpnHostView(
    val tunnelCidr: String,
    val host: String,
)

/**
 * Proyeksi satu akun PPPoE. Password (secret) SENGAJA tidak disertakan — hanya bisa
 * diisi/reset, tak pernah dibaca balik. [planName]/[nasName] diresolusi untuk
 * tampilan agar UI tak perlu memanggil balik. [planName] `null` bila paket telah
 * dinonaktifkan/terhapus dari katalog.
 *
 * Bidang FUP untuk indikator pemakaian: [fupEnabled] menandai paket ber-FUP,
 * [fupQuotaMb] kuota periodenya, [fupThrottled] apakah akun kini sedang di-throttle,
 * dan [periodUsageMb] pemakaian akun sejak awal siklus (null bila tak dihitung, mis.
 * pada balikan aksi tunggal).
 *
 * [subscriptionPlanName] HANYA terisi bila paket akun berbeda dari paket langganan — nama paket
 * yang sebenarnya ditagih. Ini penanda selisih tagih-vs-kecepatan, bukan data tampilan biasa:
 * selama ini akun bisa lahir atau berpindah ke paket lain tanpa satu pun layar menunjukkannya,
 * jadi pelanggan yang dibayar 100 Mbps bisa berjalan di 10 Mbps bertahun-tahun tanpa ketahuan.
 * Sengaja hanya ditandai, tidak diperbaiki otomatis: menyelaraskan diam-diam akan mengubah
 * kecepatan pelanggan tanpa ada yang memutuskan.
 */
data class SubscriberAccessView(
    val id: UUID,
    val subscriptionId: UUID,
    val customerId: UUID,
    val username: String,
    val authType: String,
    /** Reservasi Framed-IP-Address untuk DHCP/Static; null untuk PPPoE/Hotspot. */
    val framedIp: String?,
    val planId: UUID,
    val planName: String?,
    val nasId: UUID?,
    val nasName: String?,
    val status: String,
    val fupEnabled: Boolean,
    val fupQuotaMb: Long?,
    val fupThrottled: Boolean,
    val periodUsageMb: Long?,
    val subscriptionPlanName: String? = null,
)

/**
 * Keadaan sesi PPPoE terkini sebuah akun — hasil "B-ras Check". [online] false berarti
 * BRAS melaporkan akun tidak sedang tersambung (atau belum pernah terpantau bila
 * [lastSeenAt] null). Waktu semuanya UTC; UI yang menyesuaikan zona.
 */
data class BrasSessionView(
    val subscriberAccessId: UUID,
    val username: String,
    val online: Boolean,
    val framedIp: String?,
    val nasId: UUID?,
    val nasName: String?,
    val nasIp: String?,
    val callingStationId: String?,
    val uptimeSeconds: Long?,
    val startedAt: Instant?,
    val lastSeenAt: Instant?,
)

/** Satu titik tren trafik siap-gambar (Mbps). Null = tak terhitung, grafik memutus garis. */
data class TrafficPoint(
    val time: Instant,
    val downMbps: Double?,
    val upMbps: Double?,
)

/**
 * Tren trafik satu akun dalam rentang [hours] jam ke belakang.
 *
 * [currentDownMbps]/[currentUpMbps] = laju titik terakhir yang terhitung (throughput
 * "sekarang", rata-rata selang cuplikan terakhir); null bila tak ada titik terhitung
 * (akun sedang offline). [totalBytes] = total pemakaian data (unggah+unduh) pada rentang,
 * sadar-reset — sumber angka "kuota terpakai".
 */
data class TrafficHistoryView(
    val subscriberAccessId: UUID,
    val hours: Int,
    val points: List<TrafficPoint>,
    val currentDownMbps: Double?,
    val currentUpMbps: Double?,
    val totalBytes: Long,
)
