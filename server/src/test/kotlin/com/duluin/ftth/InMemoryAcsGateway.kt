package com.duluin.ftth

import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.ConnectedHost
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

    val rebootCalls = CopyOnWriteArrayList<String>()
    val wifiChanges = CopyOnWriteArrayList<Pair<String, WifiChange>>()

    @Volatile
    var failing: Boolean = false

    fun reset() {
        devices.clear()
        wifi.clear()
        hosts.clear()
        rebootCalls.clear()
        wifiChanges.clear()
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
}
