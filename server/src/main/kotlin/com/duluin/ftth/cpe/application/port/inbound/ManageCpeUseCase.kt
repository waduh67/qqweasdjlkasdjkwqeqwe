package com.duluin.ftth.cpe.application.port.inbound

import com.duluin.ftth.cpe.domain.model.SpeedDirection
import java.util.UUID

/**
 * Sisi tulis module cpe: perintah ke perangkat lewat ACS. Tiap perintah menulis
 * satu baris jejak audit, berhasil maupun gagal, lalu mengembalikan hasilnya.
 */
interface ManageCpeUseCase {

    /** Menjadwalkan reboot ONT/router. */
    fun reboot(deviceId: UUID): CpeActionView

    /** Mengubah SSID dan/atau password satu jaringan WiFi. */
    fun setWifi(deviceId: UUID, command: SetWifiCommand): CpeActionView

    /** Menjalankan ping diagnostik dari perangkat ke sasaran [PingCommand.host]. */
    fun runPing(deviceId: UUID, command: PingCommand): PingDiagnosticView

    /** Menjalankan uji kecepatan TR-143 pada arah unduh/unggah. */
    fun runSpeedTest(deviceId: UUID, direction: SpeedDirection): SpeedTestDiagnosticView

    /** Memicu upgrade firmware ke berkas [UpgradeFirmwareCommand.fileName] pilihan. */
    fun upgradeFirmware(deviceId: UUID, command: UpgradeFirmwareCommand): CpeActionView

    /** Reset pabrik ONT/router — mengembalikan seluruh konfigurasi ke setelan awal. */
    fun factoryReset(deviceId: UUID): CpeActionView

    /**
     * Memaksa perangkat membuka sesi ke ACS sekarang (connection request) dan
     * menyegarkan datanya; hasilnya menandai status "ACS Connect / Not Connect".
     */
    fun refreshAcs(deviceId: UUID): AcsRefreshView
}

/**
 * Sasaran upgrade firmware dari UI. [fileName] adalah identitas berkas di ACS (dari
 * [FirmwareFileView.name]); harus salah satu yang cocok untuk model perangkat, kalau
 * tidak permintaan ditolak sebelum ACS disentuh.
 */
data class UpgradeFirmwareCommand(
    val fileName: String,
)

/**
 * Sasaran ping dari UI. [host] null/kosong berarti pakai host bawaan konfigurasi
 * (mis. 8.8.8.8) — operator umumnya cukup menekan tombol tanpa mengisi apa pun.
 */
data class PingCommand(
    val host: String?,
)

/**
 * Perubahan WiFi dari UI. [ref] menunjuk jaringan yang mana (dari
 * [CpeLiveView.wifi]); field null berarti tidak diubah.
 */
data class SetWifiCommand(
    val ref: String,
    val ssid: String?,
    val passphrase: String?,
)
