package com.duluin.ftth.cpe.adapter.inbound.web

import com.duluin.ftth.cpe.application.port.inbound.CpeActionView
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceDetail
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceView
import com.duluin.ftth.cpe.application.port.inbound.CpeLiveView
import com.duluin.ftth.cpe.application.port.inbound.CpeQuery
import com.duluin.ftth.cpe.application.port.inbound.ManageCpeUseCase
import com.duluin.ftth.cpe.application.port.inbound.PingCommand
import com.duluin.ftth.cpe.application.port.inbound.PingDiagnosticView
import com.duluin.ftth.cpe.application.port.inbound.SetWifiCommand
import com.duluin.ftth.cpe.application.port.inbound.SpeedTestDiagnosticView
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
) {
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
}

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
