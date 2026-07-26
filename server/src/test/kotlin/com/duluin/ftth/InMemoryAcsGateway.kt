package com.duluin.ftth

import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.ConnectedHost
import com.duluin.ftth.cpe.domain.model.FirmwareFile
import com.duluin.ftth.cpe.domain.model.PingDiagnostic
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import com.duluin.ftth.cpe.domain.model.SpeedTestDiagnostic
import com.duluin.ftth.cpe.domain.model.WifiNetwork
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GenieACS tiruan untuk test: menyimpan device/WiFi/host di memori dan merekam
 * perintah yang dikirim, tanpa perlu ACS asli. Menggantikan `GenieAcsGateway` yang
 * aktif hanya di profil non-test.
 *
 * Karena singleton dipakai lintas test, panggil [reset] di awal tiap test. [failing]
 * membuat perintah melempar — untuk menguji jalur audit FAILED yang sengaja tidak
 * menggagalkan transaksi.
 */
@Component
@Profile("test")
class InMemoryAcsGateway : AcsGateway {

    private val devices = ConcurrentHashMap<String, AcsDevice>()
    private val wifi = ConcurrentHashMap<String, MutableList<WifiNetwork>>()
    private val hosts = ConcurrentHashMap<String, List<ConnectedHost>>()
    private val pings = ConcurrentHashMap<String, PingDiagnostic>()
    private val speedTests = ConcurrentHashMap<String, MutableMap<SpeedDirection, SpeedTestDiagnostic>>()
    private val firmware = CopyOnWriteArrayList<FirmwareFile>()

    val rebootCalls = CopyOnWriteArrayList<String>()
    val wifiChanges = CopyOnWriteArrayList<Pair<String, WifiChange>>()
    val pingCalls = CopyOnWriteArrayList<Triple<String, String, Int>>()
    val speedTestCalls = CopyOnWriteArrayList<Pair<String, SpeedDirection>>()
    val firmwarePushes = CopyOnWriteArrayList<Pair<String, String>>()

    @Volatile
    var failing: Boolean = false

    fun reset() {
        devices.clear()
        wifi.clear()
        hosts.clear()
        pings.clear()
        speedTests.clear()
        firmware.clear()
        rebootCalls.clear()
        wifiChanges.clear()
        pingCalls.clear()
        speedTestCalls.clear()
        firmwarePushes.clear()
        failing = false
    }

    fun seedDevice(device: AcsDevice) {
        devices[device.genieacsId] = device
    }

    fun seedWifi(genieacsId: String, networks: List<WifiNetwork>) {
        wifi[genieacsId] = networks.toMutableList()
    }

    fun seedHosts(genieacsId: String, list: List<ConnectedHost>) {
        hosts[genieacsId] = list
    }

    fun seedPing(genieacsId: String, result: PingDiagnostic) {
        pings[genieacsId] = result
    }

    fun seedSpeedTest(genieacsId: String, result: SpeedTestDiagnostic) {
        speedTests.getOrPut(genieacsId) { ConcurrentHashMap() }[result.direction] = result
    }

    fun seedFirmware(files: List<FirmwareFile>) {
        firmware.clear()
        firmware.addAll(files)
    }

    override fun listDevices(): List<AcsDevice> = devices.values.toList()

    override fun findDevice(genieacsId: String): AcsDevice? = devices[genieacsId]

    override fun wifiNetworks(genieacsId: String): List<WifiNetwork> = wifi[genieacsId].orEmpty()

    override fun connectedHosts(genieacsId: String): List<ConnectedHost> = hosts[genieacsId].orEmpty()

    override fun reboot(genieacsId: String) {
        if (failing) throw IllegalStateException("ACS menolak reboot (uji)")
        rebootCalls += genieacsId
    }

    override fun applyWifi(genieacsId: String, change: WifiChange) {
        if (failing) throw IllegalStateException("ACS menolak ubah WiFi (uji)")
        wifiChanges += genieacsId to change
        // Pantulkan perubahan ke state agar GET /live menampilkan nilai baru.
        val list = wifi[genieacsId] ?: return
        val idx = list.indexOfFirst { it.ref == change.ref }
        if (idx >= 0) {
            val current = list[idx]
            list[idx] = current.copy(
                ssid = change.ssid ?: current.ssid,
                passphrase = change.passphrase ?: current.passphrase,
            )
        }
    }

    override fun runPing(genieacsId: String, host: String, count: Int): PingDiagnostic {
        if (failing) throw IllegalStateException("ACS menolak ping (uji)")
        pingCalls += Triple(genieacsId, host, count)
        return pings[genieacsId]?.copy(host = host)
            ?: PingDiagnostic(host, PingDiagnostic.COMPLETE, count, 0, 12, 9, 18)
    }

    override fun runSpeedTest(genieacsId: String, direction: SpeedDirection): SpeedTestDiagnostic {
        if (failing) throw IllegalStateException("ACS menolak uji kecepatan (uji)")
        speedTestCalls += genieacsId to direction
        return speedTests[genieacsId]?.get(direction)
            ?: SpeedTestDiagnostic(
                direction = direction,
                state = PingDiagnostic.COMPLETE,
                throughputMbps = if (direction == SpeedDirection.DOWNLOAD) 94.2 else 41.7,
                testBytes = 10_485_760,
                durationMs = 900,
            )
    }

    override fun availableFirmware(productClass: String?, oui: String?): List<FirmwareFile> =
        firmware.filter { it.fileType == FirmwareFile.FIRMWARE_FILE_TYPE && it.appliesTo(productClass, oui) }

    override fun pushFirmware(genieacsId: String, file: FirmwareFile) {
        if (failing) throw IllegalStateException("ACS menolak unduh firmware (uji)")
        firmwarePushes += genieacsId to file.name
    }
}
