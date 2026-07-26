package com.duluin.ftth.cpe.application.port.outbound

import com.duluin.ftth.cpe.domain.model.ConnectedHost
import com.duluin.ftth.cpe.domain.model.PingDiagnostic
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import com.duluin.ftth.cpe.domain.model.SpeedTestDiagnostic
import com.duluin.ftth.cpe.domain.model.WifiNetwork
import java.time.Instant

/**
 * Port ke Auto Configuration Server (GenieACS) lewat NBI-nya. Satu-satunya jalan
 * module cpe menyentuh perangkat TR-069; implementasinya hidup di adapter outbound.
 *
 * Sengaja bicara dalam istilah kita ([AcsDevice], [WifiNetwork]), bukan bentuk
 * mentah NBI — pemetaan dari pohon parameter TR-069 yang ruwet (dan bercabang
 * antara model data TR-098 `InternetGatewayDevice.*` dan TR-181 `Device.*`)
 * dikurung di adapter, tak bocor ke application.
 */
interface AcsGateway {

    /**
     * Seluruh device yang dikenal ACS. Dipanggil sekali per siklus sinkronisasi
     * lalu disebar ke tiap tenant: ACS satu instance untuk semua tenant, dipetakan
     * ke pelanggan lewat serial, jadi tak ada sumbu tenant untuk memfilter di NBI.
     * Device tanpa serial (belum sempat inform lengkap) diabaikan — tak bisa ditaut.
     */
    fun listDevices(): List<AcsDevice>

    /** Satu device menurut id GenieACS; null bila sudah lenyap dari ACS. */
    fun findDevice(genieacsId: String): AcsDevice?

    /** Jaringan WiFi live perangkat — dibaca saat panel dibuka, tidak disimpan. */
    fun wifiNetworks(genieacsId: String): List<WifiNetwork>

    /** Host yang sedang tersambung ke LAN perangkat. */
    fun connectedHosts(genieacsId: String): List<ConnectedHost>

    /**
     * Menjadwalkan reboot lewat connection request. Melempar bila ACS menolak atau
     * perangkat tak terjangkau — pemanggil mencatat kegagalan itu ke jejak audit.
     */
    fun reboot(genieacsId: String)

    /** Mengubah SSID/passphrase satu jaringan WiFi. Melempar bila ACS menolak. */
    fun applyWifi(genieacsId: String, change: WifiChange)

    /**
     * Menjalankan IPPingDiagnostics: menyetel `DiagnosticsState=Requested` lalu
     * menunggu perangkat menuntaskannya (jajak pendapat berkala sampai tenggat).
     * Diagnostik TR-069 asinkron — perangkat baru melapor pada inform berikutnya.
     * Melempar bila ACS menolak/tak terjangkau; hasil tak-tuntas dikembalikan sebagai
     * [PingDiagnostic] ber-state (bukan exception) agar pemanggil bisa mencatatnya.
     */
    fun runPing(genieacsId: String, host: String, count: Int): PingDiagnostic

    /**
     * Menjalankan TR-143 Download/UploadDiagnostics pada [direction]. URL berkas uji
     * berasal dari konfigurasi adapter, bukan parameter — itu detail integrasi ACS.
     */
    fun runSpeedTest(genieacsId: String, direction: SpeedDirection): SpeedTestDiagnostic
}

/**
 * Snapshot mentah satu device dari ACS. Semua atribut nullable kecuali identitas:
 * perangkat yang baru inform mungkin belum melaporkan seluruh parameter.
 */
data class AcsDevice(
    val genieacsId: String,
    val serialNumber: String,
    val oui: String?,
    val productClass: String?,
    val manufacturer: String?,
    val model: String?,
    val softwareVersion: String?,
    val ipAddress: String?,
    val lastInformAt: Instant?,
)

/** Perubahan satu jaringan WiFi. Field null berarti "biarkan apa adanya". */
data class WifiChange(
    /** Path instance WLANConfiguration, dari [WifiNetwork.ref]. */
    val ref: String,
    val ssid: String?,
    val passphrase: String?,
)
