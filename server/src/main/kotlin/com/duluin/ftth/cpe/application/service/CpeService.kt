package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.cpe.application.port.inbound.CpeActionView
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceDetail
import com.duluin.ftth.cpe.application.port.inbound.CpeDeviceView
import com.duluin.ftth.cpe.application.port.inbound.CpeLiveView
import com.duluin.ftth.cpe.application.port.inbound.CpeQuery
import com.duluin.ftth.cpe.application.port.inbound.HostView
import com.duluin.ftth.cpe.application.port.inbound.ManageCpeUseCase
import com.duluin.ftth.cpe.application.port.inbound.SetWifiCommand
import com.duluin.ftth.cpe.application.port.inbound.WifiView
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.CpeActionLogRepository
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.CpeActionLog
import com.duluin.ftth.cpe.domain.model.CpeActionType
import com.duluin.ftth.cpe.domain.model.CpeDevice
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
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
