package com.duluin.ftth.cpe.domain.model

/** Arah uji kecepatan TR-143: mengunduh (download) atau mengunggah (upload). */
enum class SpeedDirection { DOWNLOAD, UPLOAD }

/**
 * Hasil IPPingDiagnostics (TR-069): perangkat mem-ping [host] sekian kali lalu
 * melaporkan cacahan sukses/gagal dan waktu respons. [state] mengikuti
 * `DiagnosticsState` perangkat — "Complete" bila tuntas, atau kode error
 * ("Error_CannotResolveHostName", dst) bila gagal. Metrik null bila belum/tak tuntas.
 */
data class PingDiagnostic(
    val host: String,
    val state: String,
    val successCount: Int?,
    val failureCount: Int?,
    val averageResponseMs: Int?,
    val minimumResponseMs: Int?,
    val maximumResponseMs: Int?,
) {
    /** Tuntas dan hasilnya terbaca — dasar audit SUCCESS vs FAILED. */
    val complete: Boolean get() = state == COMPLETE

    companion object {
        const val COMPLETE = "Complete"

        /** Hasil kosong saat perangkat tak menuntaskan diagnostik dalam tenggat. */
        fun incomplete(host: String, state: String) =
            PingDiagnostic(host, state, null, null, null, null, null)
    }
}

/**
 * Hasil Download/UploadDiagnostics (TR-143): perangkat mengunduh/mengunggah berkas
 * uji, [throughputMbps] dihitung adapter dari byte terukur dibagi durasi (EOM−BOM).
 * Metrik null bila diagnostik tak tuntas atau perangkat tak melaporkan byte/waktu.
 */
data class SpeedTestDiagnostic(
    val direction: SpeedDirection,
    val state: String,
    val throughputMbps: Double?,
    val testBytes: Long?,
    val durationMs: Long?,
) {
    val complete: Boolean get() = state == PingDiagnostic.COMPLETE

    companion object {
        fun incomplete(direction: SpeedDirection, state: String) =
            SpeedTestDiagnostic(direction, state, null, null, null)
    }
}
