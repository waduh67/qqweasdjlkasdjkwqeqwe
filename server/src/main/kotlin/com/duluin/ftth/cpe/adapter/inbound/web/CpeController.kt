package com.duluin.ftth.cpe.adapter.inbound.web

import com.duluin.ftth.cpe.application.port.inbound.AcsActivityView
import com.duluin.ftth.cpe.application.port.inbound.AcsBulkRefreshView
import com.duluin.ftth.cpe.application.port.inbound.AcsConsoleQuery
import com.duluin.ftth.cpe.application.port.inbound.AcsDeviceFilter
import com.duluin.ftth.cpe.application.port.inbound.AcsDeviceRowView
import com.duluin.ftth.cpe.application.port.inbound.AcsHealthView
import com.duluin.ftth.cpe.application.port.inbound.AcsRefreshView
import com.duluin.ftth.cpe.application.port.inbound.AcsServerInfoView
import com.duluin.ftth.cpe.application.port.inbound.AcsSignalFilter
import com.duluin.ftth.cpe.application.port.inbound.AcsStatsView
import com.duluin.ftth.cpe.application.port.inbound.AcsStatusFilter
import com.duluin.ftth.cpe.application.port.inbound.CpeActionView
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceDetail
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceView
import com.duluin.ftth.cpe.application.port.inbound.CpeLiveView
import com.duluin.ftth.cpe.application.port.inbound.CpeQuery
import com.duluin.ftth.cpe.application.port.inbound.FirmwareFileView
import com.duluin.ftth.cpe.application.port.inbound.ManageCpeUseCase
import com.duluin.ftth.cpe.application.port.inbound.PingCommand
import com.duluin.ftth.cpe.application.port.inbound.PingDiagnosticView
import com.duluin.ftth.cpe.application.port.inbound.RefreshAcsFleetUseCase
import com.duluin.ftth.cpe.application.port.inbound.SetWifiCommand
import com.duluin.ftth.cpe.application.port.inbound.SpeedTestDiagnosticView
import com.duluin.ftth.cpe.application.port.inbound.UpgradeFirmwareCommand
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Kelola & pantau CPE (router/ONT) pelanggan lewat GenieACS.
 *
 * Daftar & detail dibaca dari proyeksi tersimpan (cepat); keadaan langsung
 * (`/live` — WiFi & host) dipisah karena memanggil ACS, jadi UI memuatnya hanya
 * saat panel dibuka. Aksi (reboot, ubah WiFi) selalu menulis jejak audit.
 */
@RestController
@RequestMapping("/api/cpe")
@Tag(name = "CPE — Router pelanggan")
@SecurityRequirement(name = "bearer-jwt")
class CpeController(
    private val query: CpeQuery,
    private val manage: ManageCpeUseCase,
    private val console: AcsConsoleQuery,
    private val fleet: RefreshAcsFleetUseCase,
) {
    /**
     * SENGAJA tetap mewajibkan `customerId`. Melonggarkannya jadi opsional akan diam-diam
     * mengubah pembacaan per-pelanggan menjadi se-tenant di balik izin yang sama —
     * pandangan se-armada punya pintunya sendiri di `/acs/devices`.
     */
    @GetMapping("/devices")
    @PreAuthorize("@authz.can('cpe.device.view')")
    @Operation(summary = "CPE milik satu pelanggan")
    fun devicesForCustomer(@RequestParam customerId: UUID): List<CpeDeviceView> =
        query.devicesForCustomer(customerId)

    @GetMapping("/devices/{id}")
    @PreAuthorize("@authz.can('cpe.device.view')")
    @Operation(summary = "Detail satu CPE beserta riwayat aksi terakhir")
    fun get(@PathVariable id: UUID): CpeDeviceDetail = query.get(id)

    @GetMapping("/devices/{id}/live")
    @PreAuthorize("@authz.can('cpe.wifi.view')")
    @Operation(summary = "Keadaan langsung dari ACS: jaringan WiFi & host tersambung")
    fun live(@PathVariable id: UUID): CpeLiveView = query.liveState(id)

    @PostMapping("/devices/{id}/reboot")
    @PreAuthorize("@authz.can('cpe.device.reboot')")
    @Operation(summary = "Jadwalkan reboot; hasilnya (berhasil/gagal) tercatat di jejak audit")
    fun reboot(@PathVariable id: UUID): CpeActionView = manage.reboot(id)

    @PostMapping("/devices/{id}/wifi")
    @PreAuthorize("@authz.can('cpe.wifi.manage')")
    @Operation(summary = "Ubah SSID dan/atau password satu jaringan WiFi")
    fun setWifi(
        @PathVariable id: UUID,
        @Valid @RequestBody request: SetWifiRequest,
    ): CpeActionView = manage.setWifi(id, request.toCommand())

    @PostMapping("/devices/{id}/diagnostics/ping")
    @PreAuthorize("@authz.can('cpe.diagnostic.run')")
    @Operation(summary = "Jalankan ping diagnostik (IPPingDiagnostics); hasil & jejak audit dikembalikan")
    fun ping(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: PingRequest?,
    ): PingDiagnosticView = manage.runPing(id, PingCommand(request?.host))

    @PostMapping("/devices/{id}/diagnostics/speedtest")
    @PreAuthorize("@authz.can('cpe.diagnostic.run')")
    @Operation(summary = "Jalankan uji kecepatan TR-143; arah unduh (default) atau unggah")
    fun speedTest(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "DOWNLOAD") direction: SpeedDirection,
    ): SpeedTestDiagnosticView = manage.runSpeedTest(id, direction)

    @GetMapping("/devices/{id}/firmware")
    @PreAuthorize("@authz.can('cpe.firmware.manage')")
    @Operation(summary = "Berkas firmware di ACS yang cocok untuk model perangkat ini")
    fun firmware(@PathVariable id: UUID): List<FirmwareFileView> = query.availableFirmware(id)

    @PostMapping("/devices/{id}/firmware")
    @PreAuthorize("@authz.can('cpe.firmware.manage')")
    @Operation(summary = "Picu upgrade firmware ke berkas pilihan; hasil & jejak audit dikembalikan")
    fun upgradeFirmware(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpgradeFirmwareRequest,
    ): CpeActionView = manage.upgradeFirmware(id, UpgradeFirmwareCommand(request.fileName))

    @PostMapping("/devices/{id}/factory-reset")
    @PreAuthorize("@authz.can('cpe.device.manage')")
    @Operation(summary = "Reset pabrik ONT/router; hasilnya (berhasil/gagal) tercatat di jejak audit")
    fun factoryReset(@PathVariable id: UUID): CpeActionView = manage.factoryReset(id)

    @PostMapping("/devices/{id}/refresh")
    @PreAuthorize("@authz.can('cpe.device.manage')")
    @Operation(summary = "Paksa perangkat membuka sesi ke ACS sekarang; kembalikan status ACS Connect")
    fun refreshAcs(@PathVariable id: UUID): AcsRefreshView = manage.refreshAcs(id)

    // ---------------------------------------------------------------------------------
    // Konsol ACS se-armada (halaman /acs).
    //
    // Dua izin yang berbeda dengan sengaja: `cpe.acs.view` hanya membuka INFO SERVER —
    // nilai env global tanpa sebutir pun data tenant — dan itulah yang dipegang teknisi
    // agar bisa menyetel ONT di rumah pelanggan. Segala yang menyentuh armada tenant
    // tetap di balik `cpe.device.view`.
    // ---------------------------------------------------------------------------------

    @GetMapping("/acs/server")
    @PreAuthorize("@authz.can('cpe.acs.view')")
    @Operation(summary = "Setelan TR-069 yang harus diketik ke ONT pelanggan (nilai global dari env)")
    fun acsServer(): AcsServerInfoView = console.serverInfo()

    @GetMapping("/acs/health")
    @PreAuthorize("@authz.can('cpe.acs.view')")
    @Operation(summary = "Probe kesehatan server ACS; hasilnya dimemoisasi sepuluh detik")
    fun acsHealth(): AcsHealthView = console.health()

    @GetMapping("/acs/stats")
    @PreAuthorize("@authz.can('cpe.device.view')")
    @Operation(summary = "Ringkasan armada tenant: online, offline, rata-rata RX, sinkron terakhir")
    fun acsStats(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "ALL") status: AcsStatusFilter,
        @RequestParam(defaultValue = "ALL") signal: AcsSignalFilter,
        @RequestParam(required = false) brand: String?,
    ): AcsStatsView = console.stats(AcsDeviceFilter(q, status, signal, brand))

    @GetMapping("/acs/devices")
    @PreAuthorize("@authz.can('cpe.device.view')")
    @Operation(summary = "Tabel seluruh CPE tenant: identitas, SSID, PPPoE, RX/TX, suhu")
    fun acsDevices(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "ALL") status: AcsStatusFilter,
        @RequestParam(defaultValue = "ALL") signal: AcsSignalFilter,
        @RequestParam(required = false) brand: String?,
    ): List<AcsDeviceRowView> = console.devices(AcsDeviceFilter(q, status, signal, brand))

    @GetMapping("/acs/devices.csv", produces = ["text/csv"])
    @PreAuthorize("@authz.can('cpe.device.view')")
    @Operation(summary = "Ekspor tabel CPE sebagai CSV (tanpa kredensial apa pun)")
    fun acsDevicesCsv(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "ALL") status: AcsStatusFilter,
        @RequestParam(defaultValue = "ALL") signal: AcsSignalFilter,
        @RequestParam(required = false) brand: String?,
    ): ResponseEntity<ByteArray> {
        val csv = AcsDeviceCsv.render(console.devices(AcsDeviceFilter(q, status, signal, brand)))
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"perangkat-acs.csv\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csv.toByteArray(Charsets.UTF_8))
    }

    @GetMapping("/acs/logs")
    @PreAuthorize("@authz.can('cpe.device.view')")
    @Operation(summary = "Jejak aksi ACS terbaru lintas perangkat tenant")
    fun acsLogs(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false) deviceId: UUID?,
    ): List<AcsActivityView> = console.activity(limit, deviceId)

    @PostMapping("/acs/refresh-all")
    @PreAuthorize("@authz.can('cpe.device.manage')")
    @Operation(summary = "Sapuan connection request berplafon ke perangkat online (bukan 'semua')")
    fun acsRefreshAll(): AcsBulkRefreshView = fleet.refreshAll()
}

/**
 * Penulis CSV tabel perangkat ACS — satu-satunya tempat urutan & escaping kolomnya hidup.
 *
 * TIDAK ADA kolom kredensial di sini, dan tak boleh ada: berkas ini beredar lewat email
 * dan grup WhatsApp, jauh dari kendali izin aplikasi.
 */
internal object AcsDeviceCsv {

    private val HEADER = listOf(
        "serial_number", "customer_name", "manufacturer", "model", "software_version",
        "status", "last_inform", "ip_address", "ssid", "pppoe_username", "pppoe_status",
        "rx_power_dbm", "tx_power_dbm", "temperature_c",
    )

    fun render(rows: List<AcsDeviceRowView>): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(",") { escape(it) }).append("\r\n")
        for (row in rows) {
            sb.append(
                listOf(
                    row.serialNumber,
                    row.customerName.orEmpty(),
                    row.manufacturer.orEmpty(),
                    row.model.orEmpty(),
                    row.softwareVersion.orEmpty(),
                    if (row.online) "ONLINE" else "OFFLINE",
                    row.lastInformAt?.toString().orEmpty(),
                    row.ipAddress.orEmpty(),
                    row.ssid.orEmpty(),
                    row.pppoeUsername.orEmpty(),
                    row.pppoeOnline?.let { if (it) "ONLINE" else "OFFLINE" }.orEmpty(),
                    row.rxPowerDbm?.toString().orEmpty(),
                    row.txPowerDbm?.toString().orEmpty(),
                    row.temperatureC?.toString().orEmpty(),
                ).joinToString(",") { escape(it) },
            ).append("\r\n")
        }
        return sb.toString()
    }

    /** Escaping RFC-4180: bungkus kutip bila ada koma/kutip/baris-baru; kutip digandakan. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}

/** Berkas firmware sasaran, dari `FirmwareFileView.name`. */
data class UpgradeFirmwareRequest(
    @field:NotBlank val fileName: String,
)

/** Sasaran ping opsional; kosong berarti pakai host bawaan konfigurasi. */
data class PingRequest(
    val host: String?,
)

/**
 * [ref] menunjuk jaringan WiFi yang mana (dari `CpeLiveView.wifi[].ref`); [ssid]
 * dan [passphrase] null berarti "biarkan apa adanya". Batasan nilai (panjang SSID,
 * panjang password) ditegakkan di service agar konsisten dengan aturan domain.
 */
data class SetWifiRequest(
    @field:NotBlank val ref: String,
    val ssid: String?,
    val passphrase: String?,
) {
    fun toCommand() = SetWifiCommand(ref = ref, ssid = ssid, passphrase = passphrase)
}
