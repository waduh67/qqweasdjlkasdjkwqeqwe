package com.duluin.ftth.cpe.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Bentuk baca module cpe untuk lapisan web. Dikumpulkan dalam satu berkas karena
 * semuanya DTO murni yang saling terkait: device → keadaan langsung → aksi.
 */

/** Proyeksi satu CPE beserta status online yang dihitung dari inform terakhir. */
data class CpeDeviceView(
    val id: UUID,
    val genieacsId: String,
    val serialNumber: String,
    val customerId: UUID?,
    val onuId: UUID?,
    val oui: String?,
    val productClass: String?,
    val manufacturer: String?,
    val model: String?,
    val softwareVersion: String?,
    val ipAddress: String?,
    val lastInformAt: Instant?,
    /** Dihitung dari [lastInformAt] terhadap ambang basi saat query, bukan tersimpan. */
    val online: Boolean,
)

/** Detail satu device untuk halaman, dengan sejumlah aksi terakhir sebagai jejak. */
data class CpeDeviceDetail(
    val device: CpeDeviceView,
    val recentActions: List<CpeActionView>,
)

/** Keadaan langsung dari ACS — tidak tersimpan, dibaca saat panel dibuka. */
data class CpeLiveView(
    val wifi: List<WifiView>,
    val hosts: List<HostView>,
)

data class WifiView(
    /** Penunjuk jaringan untuk perintah balik; tak ditampilkan ke pengguna. */
    val ref: String,
    val ssid: String,
    /** Null bila firmware menyembunyikan kunci — UI menampilkan "tersembunyi". */
    val passphrase: String?,
    val band: String?,
    val enabled: Boolean,
)

data class HostView(
    val hostName: String?,
    val ipAddress: String?,
    val macAddress: String?,
    val active: Boolean,
)

/** Satu baris jejak audit aksi ke perangkat. */
data class CpeActionView(
    val id: UUID,
    /** REBOOT, SET_WIFI, PING_TEST, atau SPEED_TEST. */
    val action: String,
    /** SUCCESS atau FAILED. */
    val status: String,
    val detail: String?,
    val requestedBy: UUID,
    val requestedByEmail: String?,
    val requestedAt: Instant,
)

/**
 * Hasil ping diagnostik untuk UI — tidak tersimpan, dikembalikan langsung dari
 * pemanggilan. [ok] menandai diagnostik tuntas dan metriknya terbaca; bila false,
 * [message] menjelaskan sebabnya (ACS tak terjangkau, atau perangkat tak menuntaskan).
 */
data class PingDiagnosticView(
    val ok: Boolean,
    val host: String,
    /** `DiagnosticsState` perangkat, mis. "Complete" atau "Error_CannotResolveHostName". */
    val state: String,
    val successCount: Int?,
    val failureCount: Int?,
    val averageResponseMs: Int?,
    val minimumResponseMs: Int?,
    val maximumResponseMs: Int?,
    val message: String,
)

/** Hasil uji kecepatan TR-143 untuk UI; [throughputMbps] dihitung dari byte/waktu. */
data class SpeedTestDiagnosticView(
    val ok: Boolean,
    /** DOWNLOAD atau UPLOAD. */
    val direction: String,
    val state: String,
    val throughputMbps: Double?,
    val testBytes: Long?,
    val durationMs: Long?,
    val message: String,
)

/** Satu berkas firmware yang bisa dipilih sebagai sasaran upgrade perangkat. */
data class FirmwareFileView(
    /** Identitas berkas di ACS; dikirim balik saat memicu upgrade. */
    val name: String,
    val version: String?,
    val productClass: String?,
    val sizeBytes: Long?,
)
