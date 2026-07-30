package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.cpe.CpeDeviceStatusRef
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.domain.model.CpeDevice
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Implementasi [CpeApi] untuk modul lain (mis. aggregator Subscriber-360). Membaca
 * proyeksi CPE tersimpan dan menghitung online terhadap ambang basi yang sama dengan
 * UI cpe (`ftth.cpe.online-stale-after`) — murni baca, tak menyentuh ACS.
 * [CpeDeviceRepository] tenant-aware (RLS) → hasil ter-scope tenant aktif.
 */
@Service
@Transactional(readOnly = true)
class CpeApiService(
    private val deviceRepository: CpeDeviceRepository,
    @Value("\${ftth.cpe.online-stale-after:PT15M}") private val onlineStaleAfter: Duration,
) : CpeApi {

    override fun findDevicesForCustomer(customerId: UUID): List<CpeDeviceStatusRef> {
        val now = Instant.now()
        return deviceRepository.findByCustomerId(customerId).map { it.toStatusRef(now) }
    }

    private fun CpeDevice.toStatusRef(now: Instant): CpeDeviceStatusRef = CpeDeviceStatusRef(
        deviceId = id,
        serialNumber = serialNumber,
        manufacturer = manufacturer,
        model = model,
        softwareVersion = softwareVersion,
        ipAddress = ipAddress,
        lastInformAt = lastInformAt,
        online = isOnline(now, onlineStaleAfter),
    )
}
