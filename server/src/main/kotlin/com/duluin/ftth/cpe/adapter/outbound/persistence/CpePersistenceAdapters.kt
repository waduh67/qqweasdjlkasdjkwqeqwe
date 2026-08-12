package com.duluin.ftth.cpe.adapter.outbound.persistence

import com.duluin.ftth.cpe.application.port.outbound.CpeActionLogRepository
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.domain.model.CpeActionLog
import com.duluin.ftth.cpe.domain.model.CpeDevice
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CpeDevicePersistenceAdapter(
    private val jpa: CpeDeviceJpaRepository,
) : CpeDeviceRepository {

    override fun save(device: CpeDevice): CpeDevice {
        val entity = jpa.findById(device.id).orElse(null)?.apply {
            // Identitas (genieacsId, serialNumber) tak disentuh — hanya keadaan & tautan.
            oui = device.oui
            productClass = device.productClass
            manufacturer = device.manufacturer
            model = device.model
            softwareVersion = device.softwareVersion
            ipAddress = device.ipAddress
            lastInformAt = device.lastInformAt
            ssid = device.ssid
            temperatureC = device.temperatureC
            customerId = device.customerId
            onuId = device.onuId
        } ?: CpeDeviceJpaEntity(
            id = device.id,
            genieacsId = device.genieacsId,
            serialNumber = device.serialNumber,
            oui = device.oui,
            productClass = device.productClass,
            manufacturer = device.manufacturer,
            model = device.model,
            softwareVersion = device.softwareVersion,
            ipAddress = device.ipAddress,
            lastInformAt = device.lastInformAt,
            ssid = device.ssid,
            temperatureC = device.temperatureC,
            customerId = device.customerId,
            onuId = device.onuId,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): CpeDevice? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByGenieacsId(genieacsId: String): CpeDevice? =
        jpa.findByGenieacsId(genieacsId)?.toDomain()

    override fun findByCustomerId(customerId: UUID): List<CpeDevice> =
        jpa.findByCustomerId(customerId).map { it.toDomain() }

    override fun findAllForCurrentTenant(): List<CpeDevice> =
        jpa.findAll().map { it.toDomain() }

    override fun findByIds(ids: Collection<UUID>): List<CpeDevice> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids).map { it.toDomain() }

    override fun deleteByIds(ids: Collection<UUID>) {
        jpa.deleteAllById(ids)
    }
}

@Component
class CpeActionLogPersistenceAdapter(
    private val jpa: CpeActionLogJpaRepository,
) : CpeActionLogRepository {

    override fun save(log: CpeActionLog): CpeActionLog =
        jpa.save(
            CpeActionLogJpaEntity(
                id = log.id,
                deviceId = log.deviceId,
                action = log.action,
                status = log.status,
                detail = log.detail,
                requestedBy = log.requestedBy,
                requestedByEmail = log.requestedByEmail,
                requestedAt = log.requestedAt,
            ),
        ).toDomain()

    override fun findByDeviceId(deviceId: UUID): List<CpeActionLog> =
        jpa.findByDeviceIdOrderByRequestedAtDesc(deviceId).map { it.toDomain() }

    override fun findRecentForCurrentTenant(limit: Int): List<CpeActionLog> =
        jpa.findAllByOrderByRequestedAtDesc(PageRequest.of(0, limit)).map { it.toDomain() }
}

private fun CpeDeviceJpaEntity.toDomain(): CpeDevice = CpeDevice.rehydrate(
    id = id,
    genieacsId = genieacsId,
    serialNumber = serialNumber,
    oui = oui,
    productClass = productClass,
    manufacturer = manufacturer,
    model = model,
    softwareVersion = softwareVersion,
    ipAddress = ipAddress,
    lastInformAt = lastInformAt,
    ssid = ssid,
    temperatureC = temperatureC,
    customerId = customerId,
    onuId = onuId,
)

private fun CpeActionLogJpaEntity.toDomain(): CpeActionLog = CpeActionLog.rehydrate(
    id = id,
    deviceId = deviceId,
    action = action,
    status = status,
    detail = detail,
    requestedBy = requestedBy,
    requestedByEmail = requestedByEmail,
    requestedAt = requestedAt,
)
