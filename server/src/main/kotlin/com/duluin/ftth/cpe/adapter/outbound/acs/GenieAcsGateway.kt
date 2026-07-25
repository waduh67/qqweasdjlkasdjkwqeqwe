package com.duluin.ftth.cpe.adapter.outbound.acs

import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.ConnectedHost
import com.duluin.ftth.cpe.domain.model.WifiNetwork
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * Adapter [AcsGateway] di atas GenieACS NBI.
 *
 * NBI mengembalikan device sebagai POHON parameter TR-069 mentah: tiap daun berupa
 * objek `{_value, _type, _timestamp}`, dan akarnya bisa TR-098 (`InternetGatewayDevice.*`)
 * atau TR-181 (`Device.*`) tergantung model data perangkat. Seluruh keruwetan itu
 * dikurung di sini — application hanya menerima [AcsDevice]/[WifiNetwork]/[ConnectedHost]
 * yang bersih.
 *
 * Kegagalan (ACS menolak, perangkat tak terjangkau) dibiarkan naik sebagai exception
 * RestClient; pemanggil ([com.duluin.ftth.cpe.application.service.CpeService]) yang
 * memutuskan mencatatnya ke jejak audit alih-alih menggagalkan transaksi.
 */
@Component
@Profile("!test")
class GenieAcsGateway(
    private val restClient: RestClient,
) : AcsGateway {

    override fun listDevices(): List<AcsDevice> {
        val array = restClient.get()
            .uri { it.path("/devices/").queryParam("projection", LIST_PROJECTION).build() }
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()
        return array.mapNotNull { it.toAcsDevice() }
    }

    override fun findDevice(genieacsId: String): AcsDevice? =
        fetchDevice(genieacsId, LIST_PROJECTION)?.toAcsDevice()

    override fun wifiNetworks(genieacsId: String): List<WifiNetwork> {
        val device = fetchDevice(genieacsId, WIFI_PROJECTION) ?: return emptyList()
        val root = device.detectRoot() ?: return emptyList()
        val wlan = device.descend("$root.LANDevice.1.WLANConfiguration")
        if (wlan.isMissingNode) return emptyList()
        return wlan.instanceKeys().map { i ->
            val cfg = wlan.path(i)
            WifiNetwork(
                ref = "$root.LANDevice.1.WLANConfiguration.$i",
                ssid = cfg.param("SSID") ?: "",
                passphrase = cfg.param("KeyPassphrase") ?: cfg.param("PreSharedKey.1.KeyPassphrase"),
                band = cfg.param("Standard"),
                enabled = cfg.paramBool("Enable") ?: true,
            )
        }
    }

    override fun connectedHosts(genieacsId: String): List<ConnectedHost> {
        val device = fetchDevice(genieacsId, HOSTS_PROJECTION) ?: return emptyList()
        val root = device.detectRoot() ?: return emptyList()
        val hosts = device.descend("$root.LANDevice.1.Hosts.Host")
        if (hosts.isMissingNode) return emptyList()
        return hosts.instanceKeys().map { i ->
            val host = hosts.path(i)
            ConnectedHost(
                hostName = host.param("HostName"),
                ipAddress = host.param("IPAddress"),
                macAddress = host.param("MACAddress"),
                active = host.paramBool("Active") ?: false,
            )
        }
    }

    override fun reboot(genieacsId: String) {
        postTask(genieacsId, mapOf("name" to "reboot"))
    }

    override fun applyWifi(genieacsId: String, change: WifiChange) {
        val values = buildList {
            change.ssid?.let { add(listOf("${change.ref}.SSID", it, XSD_STRING)) }
            change.passphrase?.let { add(listOf("${change.ref}.KeyPassphrase", it, XSD_STRING)) }
        }
        if (values.isEmpty()) return
        postTask(genieacsId, mapOf("name" to "setParameterValues", "parameterValues" to values))
    }

    /** Ambil satu device penuh (untuk proyeksi tertentu) via query `_id`. */
    private fun fetchDevice(genieacsId: String, projection: String): JsonNode? =
        restClient.get()
            .uri {
                // Nilai query berisi kurung kurawal JSON; disisipkan lewat placeholder
                // {query} + build(map) agar `{`/`}`-nya tidak ditafsir sebagai variabel
                // URI template (dan tetap di-encode dengan benar).
                it.path("/devices/")
                    .queryParam("query", "{query}")
                    .queryParam("projection", projection)
                    .build(mapOf("query" to """{"_id":${quote(genieacsId)}}"""))
            }
            .retrieve()
            .body(JsonNode::class.java)
            ?.takeIf { it.isArray && !it.isEmpty }
            ?.path(0)

    /**
     * Kirim task ke device dengan `connection_request` agar GenieACS langsung
     * memicu koneksi ke perangkat (bukan menunggu inform berikutnya). Status non-2xx
     * dilempar oleh handler default RestClient.
     */
    private fun postTask(genieacsId: String, task: Map<String, Any>) {
        restClient.post()
            .uri { it.pathSegment("devices", genieacsId, "tasks").queryParam("connection_request").build() }
            .contentType(MediaType.APPLICATION_JSON)
            .body(task)
            .retrieve()
            .toBodilessEntity()
    }

    private fun JsonNode.toAcsDevice(): AcsDevice? {
        val genieacsId = plain("_id") ?: return null
        val serial = plain("_deviceId._SerialNumber") ?: return null
        val root = detectRoot()
        return AcsDevice(
            genieacsId = genieacsId,
            serialNumber = serial,
            oui = plain("_deviceId._OUI"),
            productClass = plain("_deviceId._ProductClass"),
            manufacturer = plain("_deviceId._Manufacturer"),
            model = root?.let { param("$it.DeviceInfo.ModelName") } ?: plain("_deviceId._ProductClass"),
            softwareVersion = root?.let { param("$it.DeviceInfo.SoftwareVersion") },
            ipAddress = root?.let { externalIp(it) },
            lastInformAt = plain("_lastInform")?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    /** IP publik WAN — coba IP-connection dulu, lalu PPP; keduanya jamak pada ONT. */
    private fun JsonNode.externalIp(root: String): String? =
        param("$root.WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ExternalIPAddress")
            ?: param("$root.WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ExternalIPAddress")

    /** Akar model data yang dipakai perangkat ini; null bila keduanya tak ada. */
    private fun JsonNode.detectRoot(): String? = when {
        !descend(ROOT_TR098).isMissingNode -> ROOT_TR098
        !descend(ROOT_TR181).isMissingNode -> ROOT_TR181
        else -> null
    }

    companion object {
        private const val ROOT_TR098 = "InternetGatewayDevice"
        private const val ROOT_TR181 = "Device"
        private const val XSD_STRING = "xsd:string"

        // Proyeksi membatasi field yang ditarik NBI agar sinkronisasi ringan di skala besar.
        private val LIST_PROJECTION = listOf(
            "_id", "_lastInform", "_deviceId",
            "$ROOT_TR098.DeviceInfo.ModelName", "$ROOT_TR098.DeviceInfo.SoftwareVersion",
            "$ROOT_TR098.WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ExternalIPAddress",
            "$ROOT_TR098.WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ExternalIPAddress",
            "$ROOT_TR181.DeviceInfo.ModelName", "$ROOT_TR181.DeviceInfo.SoftwareVersion",
        ).joinToString(",")
        private val WIFI_PROJECTION = "$ROOT_TR098.LANDevice.1.WLANConfiguration,$ROOT_TR181.LANDevice.1.WLANConfiguration"
        private val HOSTS_PROJECTION = "$ROOT_TR098.LANDevice.1.Hosts.Host,$ROOT_TR181.LANDevice.1.Hosts.Host"
    }
}

// ---- Navigasi pohon parameter TR-069 (helper privat berkas ini) ----

/**
 * Telusuri path bertitik; kembalikan MissingNode bila salah satu ruas tak ada.
 *
 * Sengaja BUKAN `at` — nama itu sudah dipakai [JsonNode.at] (JSON Pointer, wajib
 * diawali '/'); member menang atas extension, jadi memakainya akan diam-diam
 * memanggil yang salah dan melempar saat runtime.
 */
private fun JsonNode.descend(dotted: String): JsonNode {
    var node: JsonNode = this
    for (segment in dotted.split('.')) node = node.path(segment)
    return node
}

/** Nilai daun POLOS (`_id`, `_lastInform`, `_deviceId.*`) — bukan parameter TR-069. */
private fun JsonNode.plain(dotted: String): String? {
    val leaf = descend(dotted)
    return if (leaf.isMissingNode || leaf.isNull) null else leaf.asString().takeIf { it.isNotBlank() }
}

/** Nilai satu PARAMETER TR-069 — daunnya objek `{_value,...}`, jadi `_value` ditambahkan. */
private fun JsonNode.param(dotted: String): String? {
    val leaf = descend("$dotted._value")
    return if (leaf.isMissingNode || leaf.isNull) null else leaf.asString().takeIf { it.isNotBlank() }
}

private fun JsonNode.paramBool(dotted: String): Boolean? {
    val leaf = descend("$dotted._value")
    return if (leaf.isMissingNode || leaf.isNull) null else leaf.asBoolean(true)
}

/** Kunci instance angka ("1","2",…) sebuah node objek; abaikan meta seperti `_object`. */
private fun JsonNode.instanceKeys(): List<String> =
    propertyNames().filter { key -> key.isNotEmpty() && key.all(Char::isDigit) }.sortedBy { it.toInt() }

/** Kutip aman sebuah string sebagai literal JSON untuk parameter query `_id`. */
private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
