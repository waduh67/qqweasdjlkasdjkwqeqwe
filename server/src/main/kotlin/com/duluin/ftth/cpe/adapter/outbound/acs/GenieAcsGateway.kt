package com.duluin.ftth.cpe.adapter.outbound.acs

import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.ConnectedHost
import com.duluin.ftth.cpe.domain.model.FirmwareFile
import com.duluin.ftth.cpe.domain.model.PingDiagnostic
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import com.duluin.ftth.cpe.domain.model.SpeedTestDiagnostic
import com.duluin.ftth.cpe.domain.model.WifiNetwork
import com.duluin.ftth.cpe.application.port.outbound.AcsProbe
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.time.Duration
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
    @Qualifier("genieAcsRestClient") private val restClient: RestClient,
    @Qualifier("genieAcsHealthRestClient") private val healthClient: RestClient,
    /**
     * Path parameter SUHU, dipisah koma. TR-069 tak membakukan suhu; tiap vendor memakai
     * `X_*` sendiri (mis. `InternetGatewayDevice.DeviceInfo.X_HW_Temperature`). Kosong =
     * projection tak dilebarkan sama sekali dan kolomnya tetap null — itu bawaannya, sebab
     * menebak path vendor cuma menambah byte per dokumen tanpa pernah menghasilkan nilai.
     */
    @Value("\${ftth.cpe.temperature-params:}")
    private val temperatureParams: String,
    @Value("\${ftth.cpe.diagnostics.download-url:http://speedtest.tele2.net/10MB.zip}")
    private val downloadUrl: String,
    @Value("\${ftth.cpe.diagnostics.upload-url:http://speedtest.tele2.net/upload.php}")
    private val uploadUrl: String,
    @Value("\${ftth.cpe.diagnostics.upload-bytes:10485760}")
    private val uploadBytes: Long,
    @Value("\${ftth.cpe.diagnostics.timeout:PT25S}")
    private val diagnosticsTimeout: Duration,
    @Value("\${ftth.cpe.diagnostics.poll-interval:PT2S}")
    private val pollInterval: Duration,
) : AcsGateway {

    /** Path suhu terkonfigurasi, sudah dipangkas & dibuang yang kosong. */
    private val temperaturePaths: List<String> =
        temperatureParams.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Projection daftar = yang baku + path suhu vendor (bila dikonfigurasi). Melebarkan
     * projection mengubah BYTE PER DOKUMEN, bukan jumlah dokumen atau round-trip, jadi
     * aman di skala besar.
     */
    private val listProjection: String = (LIST_PROJECTION + temperaturePaths).joinToString(",")

    override fun listDevices(): List<AcsDevice> {
        val array = restClient.get()
            .uri { it.path("/devices/").queryParam("projection", listProjection).build() }
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()
        return array.mapNotNull { it.toAcsDevice() }
    }

    override fun findDevice(genieacsId: String): AcsDevice? =
        fetchDevice(genieacsId, listProjection)?.toAcsDevice()

    override fun probe(): AcsProbe {
        // Permintaan termurah yang dijawab NBI: satu dokumen, satu field.
        val startedAt = System.nanoTime()
        return runCatching {
            healthClient.get()
                .uri { it.path("/devices/").queryParam("projection", "_id").queryParam("limit", 1).build() }
                .retrieve()
                .toBodilessEntity()
            AcsProbe(reachable = true, latencyMs = (System.nanoTime() - startedAt) / 1_000_000, error = null)
        }.getOrElse { failure ->
            // SENGAJA tanpa `failure.message`: exception RestClient menyisipkan URI penuh, dan
            // base URL NBI internal (mis. http://genieacs-nbi:7557) akan mendarat di browser
            // operator tenant. Nama kelas exception cukup untuk membedakan timeout dari DNS
            // gagal; pesan aslinya dicatat pemanggil ke log server.
            AcsProbe(reachable = false, latencyMs = null, error = failure.javaClass.simpleName)
        }
    }

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

    override fun runPing(genieacsId: String, host: String, count: Int): PingDiagnostic {
        val root = currentRoot(genieacsId) ?: return PingDiagnostic.incomplete(host, "Error_NoRoot")
        val base = pingBase(root)
        // Setel input + picu (DiagnosticsState=Requested) dalam satu setParameterValues:
        // perangkat menjalankan ping lalu melaporkan hasil pada inform "diagnostics complete".
        postTask(
            genieacsId,
            mapOf(
                "name" to "setParameterValues",
                "parameterValues" to listOf(
                    listOf("$base.DiagnosticsState", "Requested", XSD_STRING),
                    listOf("$base.Host", host, XSD_STRING),
                    listOf("$base.NumberOfRepetitions", count.toString(), XSD_UNSIGNED_INT),
                ),
            ),
        )
        val device = pollDiagnostic(genieacsId, "$base.DiagnosticsState", pingProjection(base))
            ?: return PingDiagnostic.incomplete(host, "Error_Timeout")
        val state = device.param("$base.DiagnosticsState") ?: "Error"
        if (state != PingDiagnostic.COMPLETE) return PingDiagnostic.incomplete(host, state)
        return PingDiagnostic(
            host = host,
            state = state,
            successCount = device.paramInt("$base.SuccessCount"),
            failureCount = device.paramInt("$base.FailureCount"),
            averageResponseMs = device.paramInt("$base.AverageResponseTime"),
            minimumResponseMs = device.paramInt("$base.MinimumResponseTime"),
            maximumResponseMs = device.paramInt("$base.MaximumResponseTime"),
        )
    }

    override fun runSpeedTest(genieacsId: String, direction: SpeedDirection): SpeedTestDiagnostic {
        val root = currentRoot(genieacsId) ?: return SpeedTestDiagnostic.incomplete(direction, "Error_NoRoot")
        val base = speedBase(root, direction)
        val inputs = buildList {
            add(listOf("$base.DiagnosticsState", "Requested", XSD_STRING))
            if (direction == SpeedDirection.DOWNLOAD) {
                add(listOf("$base.DownloadURL", downloadUrl, XSD_STRING))
            } else {
                add(listOf("$base.UploadURL", uploadUrl, XSD_STRING))
                add(listOf("$base.TestFileLength", uploadBytes.toString(), XSD_UNSIGNED_INT))
            }
        }
        postTask(genieacsId, mapOf("name" to "setParameterValues", "parameterValues" to inputs))
        val device = pollDiagnostic(genieacsId, "$base.DiagnosticsState", speedProjection(base, direction))
            ?: return SpeedTestDiagnostic.incomplete(direction, "Error_Timeout")
        val state = device.param("$base.DiagnosticsState") ?: "Error"
        if (state != PingDiagnostic.COMPLETE) return SpeedTestDiagnostic.incomplete(direction, state)
        // Byte terukur: nama parameternya beda antar arah & antar firmware — ambil yang ada.
        val bytes = when (direction) {
            SpeedDirection.DOWNLOAD ->
                device.paramLong("$base.TestBytesReceived") ?: device.paramLong("$base.TotalBytesReceived")
            SpeedDirection.UPLOAD ->
                device.paramLong("$base.TotalBytesSent") ?: device.paramLong("$base.TestBytesSent")
        }
        val durationMs = diagnosticDurationMs(device, base)
        val throughput = if (bytes != null && durationMs != null && durationMs > 0) {
            bytes * 8.0 / 1_000_000.0 / (durationMs / 1000.0)
        } else {
            null
        }
        return SpeedTestDiagnostic(direction, state, throughput, bytes, durationMs)
    }

    override fun availableFirmware(productClass: String?, oui: String?): List<FirmwareFile> {
        // GenieACS menyimpan berkas terunggah di koleksi Files; NBI /files/ mengembalikan
        // dokumen fs.files: `_id` = nama berkas, metadata {fileType, version, oui, productClass}.
        val array = restClient.get()
            .uri { it.path("/files/").build() }
            .retrieve()
            .body(JsonNode::class.java)
            ?: return emptyList()
        return array.mapNotNull { it.toFirmwareFile() }
            .filter { it.fileType == FirmwareFile.FIRMWARE_FILE_TYPE && it.appliesTo(productClass, oui) }
    }

    override fun pushFirmware(genieacsId: String, file: FirmwareFile) {
        // Task `download`: GenieACS mencari berkas by `fileName` untuk mengisi URL/ukuran,
        // lalu mengirim TR-069 Download RPC ke perangkat lewat connection request.
        postTask(
            genieacsId,
            mapOf(
                "name" to "download",
                "fileName" to file.name,
                "fileType" to file.fileType.ifBlank { FirmwareFile.FIRMWARE_FILE_TYPE },
            ),
        )
    }

    override fun factoryReset(genieacsId: String) {
        postTask(genieacsId, mapOf("name" to "factoryReset"))
    }

    override fun requestConnection(genieacsId: String): Boolean {
        // Task `refreshObject` pada akar (objectName "") + connection_request: GenieACS
        // mencoba menghubungi perangkat SEKARANG dan menyegarkan seluruh pohonnya.
        // Balasannya menandai keterjangkauan: 200 = sesi terbentuk & task jalan
        // ("ACS Connect"); 202 = perangkat tak terjangkau, task diantre ("Not Connect").
        val response = restClient.post()
            .uri { it.pathSegment("devices", genieacsId, "tasks").queryParam("connection_request").build() }
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("name" to "refreshObject", "objectName" to ""))
            .retrieve()
            .toBodilessEntity()
        return response.statusCode.value() == 200
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
            ssid = firstSsid(),
            temperatureC = firstTemperature(),
        )
    }

    /**
     * SSID jaringan pertama, dicoba berurutan lintas model data. Path ketiga adalah letak
     * TR-181 yang SEBENARNYA (`Device.WiFi.SSID.1.SSID`) — perangkat TR-181 tak menaruh WiFi
     * di bawah `LANDevice`. Dicoba juga path kedua sebab sebagian firmware hibrida memakainya.
     */
    private fun JsonNode.firstSsid(): String? =
        SSID_PATHS.firstNotNullOfOrNull { param(it) }

    /** Nilai suhu pertama yang bisa dibaca sebagai angka dari path vendor terkonfigurasi. */
    private fun JsonNode.firstTemperature(): Double? =
        temperaturePaths.firstNotNullOfOrNull { param(it)?.toDoubleOrNull() }

    /** Petakan satu dokumen fs.files GenieACS ke [FirmwareFile]; null bila tanpa nama. */
    private fun JsonNode.toFirmwareFile(): FirmwareFile? {
        val name = plain("_id") ?: return null
        val length = descend("length")
        return FirmwareFile(
            name = name,
            version = plain("metadata.version"),
            productClass = plain("metadata.productClass"),
            oui = plain("metadata.oui"),
            fileType = plain("metadata.fileType") ?: "",
            sizeBytes = if (length.isMissingNode || length.isNull) null else length.asLong(),
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

    /** Akar model data device SEKARANG (perlu fetch — dipakai untuk menyusun path diagnostik). */
    private fun currentRoot(genieacsId: String): String? =
        fetchDevice(genieacsId, listProjection)?.detectRoot()

    /**
     * Path objek IPPing diagnostics: TR-181 menaruhnya di bawah `IP.Diagnostics.IPPing`,
     * TR-098 memakai objek datar `IPPingDiagnostics` di akar.
     */
    private fun pingBase(root: String): String =
        if (root == ROOT_TR181) "$root.IP.Diagnostics.IPPing" else "$root.IPPingDiagnostics"

    /** Path objek Download/Upload diagnostics (TR-143) sesuai arah & model data. */
    private fun speedBase(root: String, direction: SpeedDirection): String {
        val leaf = if (direction == SpeedDirection.DOWNLOAD) "DownloadDiagnostics" else "UploadDiagnostics"
        return if (root == ROOT_TR181) "$root.IP.Diagnostics.$leaf" else "$root.$leaf"
    }

    /**
     * Menunggu terbatas sampai `DiagnosticsState` bukan lagi "Requested"/"None".
     *
     * Diagnostik TR-069 ASINKRON: perangkat menjalankan uji lalu melaporkan hasil pada
     * inform "diagnostics complete" berikutnya. Untuk aksi admin on-demand, polling
     * bertahap sampai [diagnosticsTimeout] dapat diterima; bila mentok, kembalikan
     * snapshot terakhir (state-nya jadi penanda "belum tuntas").
     */
    private fun pollDiagnostic(genieacsId: String, statePath: String, projection: String): JsonNode? {
        val deadline = Instant.now().plus(diagnosticsTimeout)
        var last: JsonNode? = null
        while (true) {
            last = fetchDevice(genieacsId, projection) ?: last
            val state = last?.param(statePath)
            if (state != null && state != "Requested" && state != "None") return last
            if (Instant.now().isAfter(deadline)) return last
            Thread.sleep(pollInterval.toMillis())
        }
    }

    private fun pingProjection(base: String): String = listOf(
        "$base.DiagnosticsState", "$base.SuccessCount", "$base.FailureCount",
        "$base.AverageResponseTime", "$base.MinimumResponseTime", "$base.MaximumResponseTime",
    ).joinToString(",")

    private fun speedProjection(base: String, direction: SpeedDirection): String {
        val bytes = if (direction == SpeedDirection.DOWNLOAD) {
            listOf("$base.TestBytesReceived", "$base.TotalBytesReceived")
        } else {
            listOf("$base.TotalBytesSent", "$base.TestBytesSent")
        }
        return (listOf("$base.DiagnosticsState", "$base.BOMTime", "$base.EOMTime") + bytes).joinToString(",")
    }

    /** Durasi transfer TR-143 dari `BOMTime`→`EOMTime` (ISO dateTime), dalam milidetik. */
    private fun diagnosticDurationMs(device: JsonNode, base: String): Long? {
        val bom = device.param("$base.BOMTime")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val eom = device.param("$base.EOMTime")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return Duration.between(bom, eom).toMillis().takeIf { it > 0 }
    }

    companion object {
        private const val ROOT_TR098 = "InternetGatewayDevice"
        private const val ROOT_TR181 = "Device"
        private const val XSD_STRING = "xsd:string"
        private const val XSD_UNSIGNED_INT = "xsd:unsignedInt"

        /**
         * Letak SSID pertama, diurut sesuai kemungkinan. Ketiga path ini juga masuk
         * projection daftar — hanya DAUN `.1.SSID`, bukan seluruh subpohon WLAN
         * (itu tugas [WIFI_PROJECTION] saat panel per-pelanggan dibuka).
         */
        private val SSID_PATHS = listOf(
            "$ROOT_TR098.LANDevice.1.WLANConfiguration.1.SSID",
            "$ROOT_TR181.LANDevice.1.WLANConfiguration.1.SSID",
            "$ROOT_TR181.WiFi.SSID.1.SSID",
        )

        // Proyeksi membatasi field yang ditarik NBI agar sinkronisasi ringan di skala besar.
        private val LIST_PROJECTION = listOf(
            "_id", "_lastInform", "_deviceId",
            "$ROOT_TR098.DeviceInfo.ModelName", "$ROOT_TR098.DeviceInfo.SoftwareVersion",
            "$ROOT_TR098.WANDevice.1.WANConnectionDevice.1.WANIPConnection.1.ExternalIPAddress",
            "$ROOT_TR098.WANDevice.1.WANConnectionDevice.1.WANPPPConnection.1.ExternalIPAddress",
            "$ROOT_TR181.DeviceInfo.ModelName", "$ROOT_TR181.DeviceInfo.SoftwareVersion",
        ) + SSID_PATHS
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

private fun JsonNode.paramInt(dotted: String): Int? = param(dotted)?.toIntOrNull()

private fun JsonNode.paramLong(dotted: String): Long? = param(dotted)?.toLongOrNull()

/** Kunci instance angka ("1","2",…) sebuah node objek; abaikan meta seperti `_object`. */
private fun JsonNode.instanceKeys(): List<String> =
    propertyNames().filter { key -> key.isNotEmpty() && key.all(Char::isDigit) }.sortedBy { it.toInt() }

/** Kutip aman sebuah string sebagai literal JSON untuk parameter query `_id`. */
private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
