package com.duluin.ftth.cpe.application.port.inbound

import java.util.UUID

/**
 * Sisi baca & aksi KONSOL ACS (halaman `/acs`), terpisah dari [CpeQuery] yang melayani
 * panel satu pelanggan.
 *
 * Pemisahannya bukan kosmetik: implementasinya merangkai data dari monitoring, bng, dan
 * customer sekaligus, sementara [CpeQuery] cukup dengan repo sendiri + ACS. Menggabung
 * keduanya berarti setiap panel pelanggan ikut menyeret tiga dependensi lintas-module.
 */
interface AcsConsoleQuery {

    /** Alamat & kredensial TR-069 dari env — tanpa data tenant, boleh dilihat teknisi. */
    fun serverInfo(): AcsServerInfoView

    /** Probe kesehatan ACS; hasilnya dimemoisasi sebentar karena halaman menyegarkan diri. */
    fun health(): AcsHealthView

    /** Ringkasan armada tenant aktif. */
    fun stats(filter: AcsDeviceFilter): AcsStatsView

    /** Baris tabel device, sudah tersaring. */
    fun devices(filter: AcsDeviceFilter): List<AcsDeviceRowView>

    /** Jejak aksi terbaru lintas device; [limit] diplafon di implementasi. */
    fun activity(limit: Int, deviceId: UUID?): List<AcsActivityView>
}

/** Sapuan connection request berplafon ke perangkat online tenant aktif. */
interface RefreshAcsFleetUseCase {

    fun refreshAll(): AcsBulkRefreshView
}

/**
 * Saringan tabel device. Disaring di Kotlin, BUKAN di SQL: SSID hidup di `cpe_device`,
 * PPPoE di module bng, dan RX di module monitoring — tak ada satu `WHERE` yang bisa
 * melihat ketiganya. Armada per tenant berukuran ratusan dan himpunan penuhnya memang
 * sudah dimuat tiap sinkronisasi, jadi biayanya sepadan.
 */
data class AcsDeviceFilter(
    /** Cocok sebagian pada serial, SSID, username PPPoE, atau nama pelanggan; abai besar-kecil. */
    val q: String? = null,
    val status: AcsStatusFilter = AcsStatusFilter.ALL,
    val signal: AcsSignalFilter = AcsSignalFilter.ALL,
    /** Cocok persis pada `manufacturer`. */
    val brand: String? = null,
)

enum class AcsStatusFilter { ALL, ONLINE, OFFLINE }

/**
 * Ambangnya sengaja sama dengan alarm `ONU_LOW_RX` dan penanda warna di monitoring
 * (WARNING -25 dBm, CRITICAL -27 dBm) supaya dua layar tak pernah berselisih menyebut
 * satu ONU "sinyal jelek".
 */
enum class AcsSignalFilter { ALL, GOOD, WARN, CRITICAL, UNKNOWN }
