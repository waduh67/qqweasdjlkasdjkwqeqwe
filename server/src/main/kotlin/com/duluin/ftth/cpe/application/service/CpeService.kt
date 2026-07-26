package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.cpe.application.port.inbound.AcsRefreshView
import com.duluin.ftth.cpe.application.port.inbound.CpeActionView
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceDetail
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceView
import com.duluin.ftth.cpe.application.port.inbound.CpeLiveView
import com.duluin.ftth.cpe.application.port.inbound.CpeQuery
import com.duluin.ftth.cpe.application.port.inbound.FirmwareFileView
import com.duluin.ftth.cpe.application.port.inbound.HostView
import com.duluin.ftth.cpe.application.port.inbound.ManageCpeUseCase
import com.duluin.ftth.cpe.application.port.inbound.PingCommand
import com.duluin.ftth.cpe.application.port.inbound.PingDiagnosticView
import com.duluin.ftth.cpe.application.port.inbound.SetWifiCommand
import com.duluin.ftth.cpe.application.port.inbound.SpeedTestDiagnosticView
import com.duluin.ftth.cpe.application.port.inbound.UpgradeFirmwareCommand
import com.duluin.ftth.cpe.application.port.inbound.WifiView
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.CpeActionLogRepository
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.CpeActionLog
import com.duluin.ftth.cpe.domain.model.CpeActionType
import com.duluin.ftth.cpe.domain.model.CpeDevice
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Sisi baca & aksi module cpe di atas proyeksi tersimpan + ACS.
 *
 * Perintah ke perangkat ([reboot], [setWifi]) SENGAJA tidak melempar saat ACS
 * menolak/tak terjangkau: kegagalan justru dicatat ke jejak audit dan dikembalikan
 * sebagai hasil berstatus FAILED. Melempar akan me-rollback transaksi yang sama dan
 * ikut menghapus catatan kegagalannya — padahal itulah yang paling perlu terekam.
 * Yang tetap dilempar hanyalah kesalahan pemanggil (device tak ada, input WiFi tak
 * masuk akal), sebelum ACS disentuh.
 */
@Service
@Transactional
class CpeService(
    private val deviceRepository: CpeDeviceRepository,
    private val actionLogRepository: CpeActionLogRepository,
    private val acsGateway: AcsGateway,
    private val currentUser: CurrentUserProvider,
    @Value("\${ftth.cpe.online-stale-after:PT15M}") private val onlineStaleAfter: Duration,
    @Value("\${ftth.cpe.diagnostics.ping-host:8.8.8.8}") private val defaultPingHost: String,
    @Value("\${ftth.cpe.diagnostics.ping-count:4}") private val pingCount: Int,
) : CpeQuery, ManageCpeUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun devicesForCustomer(customerId: UUID): List<CpeDeviceView> =
        deviceRepository.findByCustomerId(customerId).map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(deviceId: UUID): CpeDeviceDetail {
        val device = requireDevice(deviceId)
        val actions = actionLogRepository.findByDeviceId(deviceId).take(RECENT_ACTIONS).map { it.toView() }
        return CpeDeviceDetail(device.toView(), actions)
    }

    @Transactional(readOnly = true)
    override fun liveState(deviceId: UUID): CpeLiveView {
        val device = requireDevice(deviceId)
        val wifi = acsGateway.wifiNetworks(device.genieacsId)
            .map { WifiView(it.ref, it.ssid, it.passphrase, it.band, it.enabled) }
        val hosts = acsGateway.connectedHosts(device.genieacsId)
            .map { HostView(it.hostName, it.ipAddress, it.macAddress, it.active) }
        return CpeLiveView(wifi, hosts)
    }

    @Transactional(readOnly = true)
    override fun availableFirmware(deviceId: UUID): List<FirmwareFileView> {
        val device = requireDevice(deviceId)
        return acsGateway.availableFirmware(device.productClass, device.oui)
            .map { FirmwareFileView(it.name, it.version, it.productClass, it.sizeBytes) }
    }

    override fun reboot(deviceId: UUID): CpeActionView {
        val device = requireDevice(deviceId)
        val actor = currentUser.current()
        val outcome = runCatching { acsGateway.reboot(device.genieacsId) }
        val entry = if (outcome.isSuccess) {
            CpeActionLog.succeeded(deviceId, CpeActionType.REBOOT, "Reboot dijadwalkan", actor.userId, actor.email)
        } else {
            log.warn("Reboot device {} gagal: {}", deviceId, outcome.exceptionOrNull()?.message)
            CpeActionLog.failed(deviceId, CpeActionType.REBOOT, outcome.exceptionOrNull()?.message?.take(480), actor.userId, actor.email)
        }
        return actionLogRepository.save(entry).toView()
    }

    override fun setWifi(deviceId: UUID, command: SetWifiCommand): CpeActionView {
        val device = requireDevice(deviceId)
        val ssid = command.ssid?.trim()?.takeIf { it.isNotEmpty() }
        val passphrase = command.passphrase?.takeIf { it.isNotEmpty() }
        if (ssid == null && passphrase == null) {
            throw ValidationException("Tidak ada perubahan: isi SSID atau password WiFi")
        }
        if (ssid != null && ssid.length > 32) throw ValidationException("SSID WiFi maksimal 32 karakter")
        if (passphrase != null && passphrase.length !in 8..63) {
            throw ValidationException("Password WiFi harus 8-63 karakter")
        }

        val actor = currentUser.current()
        val summary = listOfNotNull(ssid?.let { "SSID→$it" }, passphrase?.let { "password diubah" }).joinToString(", ")
        val outcome = runCatching { acsGateway.applyWifi(device.genieacsId, WifiChange(command.ref, ssid, passphrase)) }
        val entry = if (outcome.isSuccess) {
            CpeActionLog.succeeded(deviceId, CpeActionType.SET_WIFI, summary, actor.userId, actor.email)
        } else {
            log.warn("Ubah WiFi device {} gagal: {}", deviceId, outcome.exceptionOrNull()?.message)
            CpeActionLog.failed(
                deviceId, CpeActionType.SET_WIFI,
                "$summary — gagal: ${outcome.exceptionOrNull()?.message}".take(480),
                actor.userId, actor.email,
            )
        }
        return actionLogRepository.save(entry).toView()
    }

    override fun runPing(deviceId: UUID, command: PingCommand): PingDiagnosticView {
        val device = requireDevice(deviceId)
        val host = command.host?.trim()?.takeIf { it.isNotEmpty() } ?: defaultPingHost
        if (host.length > 256) throw ValidationException("Alamat host maksimal 256 karakter")

        val actor = currentUser.current()
        val outcome = runCatching { acsGateway.runPing(device.genieacsId, host, pingCount) }
        val result = outcome.getOrNull()
        val ok = result?.complete == true
        val message = when {
            result == null -> "gagal menghubungi ACS: ${outcome.exceptionOrNull()?.message?.take(200)}"
            result.complete -> {
                val total = (result.successCount ?: 0) + (result.failureCount ?: 0)
                buildString {
                    append("${result.successCount ?: 0}/$total sukses")
                    result.averageResponseMs?.let { append(", avg $it ms") }
                }
            }
            else -> "tidak tuntas (${result.state})"
        }
        if (!ok) log.warn("Ping device {} tidak tuntas: {}", deviceId, message)
        persistAction(deviceId, CpeActionType.PING_TEST, ok, "Ping $host → $message", actor.userId, actor.email)
        return PingDiagnosticView(
            ok = ok,
            host = host,
            state = result?.state ?: "Error",
            successCount = result?.successCount,
            failureCount = result?.failureCount,
            averageResponseMs = result?.averageResponseMs,
            minimumResponseMs = result?.minimumResponseMs,
            maximumResponseMs = result?.maximumResponseMs,
            message = message,
        )
    }

    override fun runSpeedTest(deviceId: UUID, direction: SpeedDirection): SpeedTestDiagnosticView {
        val device = requireDevice(deviceId)
        val actor = currentUser.current()
        val outcome = runCatching { acsGateway.runSpeedTest(device.genieacsId, direction) }
        val result = outcome.getOrNull()
        val throughput = result?.throughputMbps
        val ok = result != null && result.complete && throughput != null
        val message = when {
            result == null -> "gagal menghubungi ACS: ${outcome.exceptionOrNull()?.message?.take(200)}"
            result.complete && throughput != null -> String.format(Locale.US, "%.1f Mbps", throughput)
            result.complete -> "tuntas tanpa throughput terbaca"
            else -> "tidak tuntas (${result.state})"
        }
        if (!ok) log.warn("Uji kecepatan {} device {} tidak tuntas: {}", direction, deviceId, message)
        val label = if (direction == SpeedDirection.DOWNLOAD) "unduh" else "unggah"
        persistAction(deviceId, CpeActionType.SPEED_TEST, ok, "Speed $label → $message", actor.userId, actor.email)
        return SpeedTestDiagnosticView(
            ok = ok,
            direction = direction.name,
            state = result?.state ?: "Error",
            throughputMbps = result?.throughputMbps,
            testBytes = result?.testBytes,
            durationMs = result?.durationMs,
            message = message,
        )
    }

    override fun upgradeFirmware(deviceId: UUID, command: UpgradeFirmwareCommand): CpeActionView {
        val device = requireDevice(deviceId)
        val fileName = command.fileName.trim()
        if (fileName.isEmpty()) throw ValidationException("Pilih berkas firmware")
        // Validasi ke daftar yang cocok untuk model ini — mencegah push berkas asal
        // (dan memberi kita fileType yang benar) sebelum ACS disentuh.
        val target = acsGateway.availableFirmware(device.productClass, device.oui)
            .firstOrNull { it.name == fileName }
            ?: throw ValidationException("Firmware '$fileName' tidak tersedia untuk perangkat ini")

        val actor = currentUser.current()
        val label = "Firmware→${target.name}" + (target.version?.let { " ($it)" } ?: "")
        val outcome = runCatching { acsGateway.pushFirmware(device.genieacsId, target) }
        val entry = if (outcome.isSuccess) {
            CpeActionLog.succeeded(deviceId, CpeActionType.FIRMWARE_UPGRADE, label, actor.userId, actor.email)
        } else {
            log.warn("Upgrade firmware device {} gagal: {}", deviceId, outcome.exceptionOrNull()?.message)
            CpeActionLog.failed(
                deviceId, CpeActionType.FIRMWARE_UPGRADE,
                "$label — gagal: ${outcome.exceptionOrNull()?.message}".take(480),
                actor.userId, actor.email,
            )
        }
        return actionLogRepository.save(entry).toView()
    }

    override fun factoryReset(deviceId: UUID): CpeActionView {
        val device = requireDevice(deviceId)
        val actor = currentUser.current()
        val outcome = runCatching { acsGateway.factoryReset(device.genieacsId) }
        val entry = if (outcome.isSuccess) {
            CpeActionLog.succeeded(deviceId, CpeActionType.FACTORY_RESET, "Reset pabrik dijadwalkan", actor.userId, actor.email)
        } else {
            log.warn("Factory reset device {} gagal: {}", deviceId, outcome.exceptionOrNull()?.message)
            CpeActionLog.failed(deviceId, CpeActionType.FACTORY_RESET, outcome.exceptionOrNull()?.message?.take(480), actor.userId, actor.email)
        }
        return actionLogRepository.save(entry).toView()
    }

    override fun refreshAcs(deviceId: UUID): AcsRefreshView {
        val device = requireDevice(deviceId)
        val actor = currentUser.current()
        val outcome = runCatching { acsGateway.requestConnection(device.genieacsId) }
        val connected = outcome.getOrNull() == true
        val message = when {
            outcome.isFailure -> "gagal menghubungi ACS: ${outcome.exceptionOrNull()?.message?.take(200)}"
            connected -> "ACS terhubung ke perangkat"
            else -> "perangkat tak menjawab; perintah diantre untuk inform berikutnya"
        }
        if (outcome.isFailure) log.warn("Refresh ACS device {} gagal: {}", deviceId, outcome.exceptionOrNull()?.message)
        // "Not Connect" BUKAN kegagalan aksi (perangkat cuma sedang offline) — selama NBI
        // menerima permintaan, aksinya SUCCESS; hanya penolakan NBI yang FAILED.
        persistAction(deviceId, CpeActionType.REFRESH_ACS, outcome.isSuccess, "Refresh ACS → $message", actor.userId, actor.email)
        return AcsRefreshView(connected, message)
    }

    /** Tulis satu baris jejak audit untuk sebuah aksi diagnostik, sukses atau gagal. */
    private fun persistAction(deviceId: UUID, type: CpeActionType, ok: Boolean, detail: String, actor: UUID, email: String?) {
        val entry = if (ok) {
            CpeActionLog.succeeded(deviceId, type, detail.take(480), actor, email)
        } else {
            CpeActionLog.failed(deviceId, type, detail.take(480), actor, email)
        }
        actionLogRepository.save(entry)
    }

    private fun requireDevice(id: UUID): CpeDevice =
        deviceRepository.findById(id) ?: throw NotFoundException("Perangkat CPE $id tidak ditemukan")

    private fun CpeDevice.toView(): CpeDeviceView = CpeDeviceView(
        id = id,
        genieacsId = genieacsId,
        serialNumber = serialNumber,
        customerId = customerId,
        onuId = onuId,
        oui = oui,
        productClass = productClass,
        manufacturer = manufacturer,
        model = model,
        softwareVersion = softwareVersion,
        ipAddress = ipAddress,
        lastInformAt = lastInformAt,
        online = isOnline(Instant.now(), onlineStaleAfter),
    )

    private fun CpeActionLog.toView(): CpeActionView = CpeActionView(
        id = id,
        action = action.name,
        status = status.name,
        detail = detail,
        requestedBy = requestedBy,
        requestedByEmail = requestedByEmail,
        requestedAt = requestedAt,
    )

    companion object {
        /** Cukup untuk panel riwayat aksi; bukan jejak audit lengkap (itu di audit_log). */
        const val RECENT_ACTIONS = 20
    }
}
