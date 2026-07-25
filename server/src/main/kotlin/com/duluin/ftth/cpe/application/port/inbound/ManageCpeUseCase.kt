package com.duluin.ftth.cpe.application.port.inbound

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
}

/**
 * Perubahan WiFi dari UI. [ref] menunjuk jaringan yang mana (dari
 * [CpeLiveView.wifi]); field null berarti tidak diubah.
 */
data class SetWifiCommand(
    val ref: String,
    val ssid: String?,
    val passphrase: String?,
)
