package com.duluin.ftth.contract

import java.time.Instant

/**
 * Protokol collector ↔ server.
 *
 * Arah koneksinya selalu dari collector KE server (outbound). ISP tidak perlu
 * membuka port atau mengatur port-forwarding, dan server tidak pernah perlu tahu
 * alamat jaringan pelanggan — ini yang membuat pemasangannya sesederhana
 * menjalankan satu proses di balik NAT.
 *
 * Setiap perubahan yang tidak kompatibel harus menaikkan [PROTOCOL_VERSION];
 * server menolak collector dengan versi mayor berbeda agar agent lama tidak
 * diam-diam mengirim data yang salah tafsir.
 */
object CollectorProtocol {
    const val PROTOCOL_VERSION = 1

    /** Header berisi API key collector. Kunci mentah tidak pernah disimpan server. */
    const val API_KEY_HEADER = "X-Collector-Key"

    const val PROTOCOL_VERSION_HEADER = "X-Collector-Protocol"
}

// ---------------------------------------------------------------------------
// Collector → server
// ---------------------------------------------------------------------------

/**
 * Denyut nadi sekaligus permintaan konfigurasi.
 *
 * Konfigurasi polling dikirim balik server sebagai jawaban, bukan disimpan di
 * berkas lokal collector: operator mengatur OLT dan interval dari UI, dan
 * collector menyesuaikan diri pada denyut berikutnya tanpa perlu di-deploy ulang.
 */
data class CollectorHeartbeat(
    val agentVersion: String,
    val protocolVersion: Int = CollectorProtocol.PROTOCOL_VERSION,
    /** Ringkasan hasil siklus polling terakhir, untuk ditampilkan di UI. */
    val lastCycle: CycleReport? = null,
)

data class CycleReport(
    val startedAt: Instant,
    val finishedAt: Instant,
    val targetsPolled: Int,
    val targetsFailed: Int,
    val readingsCollected: Int,
    /** Galat per OLT, kosong bila semua mulus. */
    val failures: List<TargetFailure> = emptyList(),
)

data class TargetFailure(
    val oltId: String,
    val oltCode: String,
    val message: String,
)

/**
 * Kiriman batch hasil polling.
 *
 * [batchId] dibuat collector dan diulang bila pengiriman gagal, sehingga server
 * bisa membuang kiriman ganda. Tanpa ini, jaringan ISP yang putus-nyambung akan
 * menghasilkan metrik dobel yang merusak agregasi.
 */
data class MetricBatch(
    val batchId: String,
    val collectedAt: Instant,
    val readings: List<OnuReading>,
) {
    companion object {
        /** Batas jumlah bacaan per kiriman, agar satu request tidak membengkak. */
        const val MAX_READINGS = 5_000
    }
}

/**
 * Satu bacaan dari sebuah ONU.
 *
 * ONU dikenali lewat [serialNumber], BUKAN id internal server: collector membaca
 * apa yang dilaporkan OLT dan tidak tahu-menahu soal basis data server. Server
 * yang memetakannya; serial yang tidak dikenal dilaporkan sebagai ONU liar
 * (perangkat terpasang di lapangan tapi belum terdaftar).
 */
data class OnuReading(
    val serialNumber: String,
    val oltCode: String,
    val ponPortLabel: String?,
    val status: OnuOperationalStatus,
    val rxPowerDbm: Double?,
    val txPowerDbm: Double?,
    val uptimeSeconds: Long?,
    /** Jarak hasil ranging OLT dalam meter; berguna untuk memperkirakan titik putus. */
    val distanceMeters: Int?,
    val observedAt: Instant,
)

/** Status ONU sebagaimana dilaporkan OLT. */
enum class OnuOperationalStatus {
    ONLINE,
    OFFLINE,
    /** Loss of Signal — fiber putus atau konektor lepas. */
    LOS,
    /** Dikenali OLT tapi belum terotorisasi. */
    UNKNOWN,
}

// ---------------------------------------------------------------------------
// Server → collector
// ---------------------------------------------------------------------------

/**
 * Konfigurasi yang dikembalikan server pada tiap denyut. Collector memperlakukan
 * ini sebagai kebenaran mutlak dan mengganti konfigurasi lokalnya.
 */
data class CollectorConfig(
    val collectorName: String,
    val pollIntervalSeconds: Int,
    val targets: List<OltTarget>,
    /** Server bisa menyuruh collector diam, mis. saat pemeliharaan. */
    val paused: Boolean = false,
)

/**
 * Satu OLT yang harus di-polling.
 *
 * [snmpCommunity] dikirim polos di dalam badan respons — aman karena kanalnya
 * TLS dan hanya collector terautentikasi yang bisa memintanya. Di database
 * server nilainya tetap tersimpan terenkripsi.
 */
data class OltTarget(
    val oltId: String,
    val oltCode: String,
    val vendor: String,
    val host: String,
    val snmpPort: Int = 161,
    val snmpCommunity: String?,
)

/** Jawaban server atas sebuah [MetricBatch]. */
data class IngestResult(
    val accepted: Int,
    /** Bacaan yang serialnya tidak dikenal — kandidat ONU liar. */
    val unknownSerialNumbers: List<String>,
    /** True bila [MetricBatch.batchId] sudah pernah diterima. */
    val duplicate: Boolean = false,
)
