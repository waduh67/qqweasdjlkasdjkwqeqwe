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

/** Tren trafik satu akun dalam rentang [hours] jam ke belakang. */
data class TrafficHistoryView(
    val subscriberAccessId: UUID,
    val hours: Int,
    val points: List<TrafficPoint>,
)
