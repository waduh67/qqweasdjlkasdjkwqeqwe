package com.duluin.ftth.cpe.adapter.outbound.persistence

import com.duluin.ftth.cpe.application.port.outbound.CpeActionLogRepository
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.domain.model.CpeActionLog
import com.duluin.ftth.cpe.domain.model.CpeDevice
import com.duluin.ftth.customer.CustomerObservationApi
import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CpeDevicePersistenceAdapter(
    private val jpa: CpeDeviceJpaRepository,
    private val bindings: CpeObservationBindingStore,
    private val episodes: CustomerObservationApi,
) : CpeDeviceRepository {

    override fun save(device: CpeDevice): CpeDevice {
        val episode = bindings.current(device) ?: throw ConflictException("CPE_EPISODE_FRESHNESS_REQUIRED")
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
        val saved = jpa.saveAndFlush(entity).toDomain()
        bindings.record(saved, episode, device.observedFieldsAt)
        return saved
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun findById(id: UUID): CpeDevice? = jpa.findById(id).orElse(null)?.toDomain()?.takeIf(bindings::visible)

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun findByGenieacsId(genieacsId: String): CpeDevice? =
        bindings.visible(jpa.findAll().filter { it.genieacsId == genieacsId }.map { it.toDomain() }).singleOrNull()

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun findByCustomerId(customerId: UUID): List<CpeDevice> =
        bindings.visible(jpa.findByCustomerId(customerId).map { it.toDomain() })

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun findAllForCurrentTenant(): List<CpeDevice> =
        bindings.visible(jpa.findAll().map { it.toDomain() })

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun findByIds(ids: Collection<UUID>): List<CpeDevice> =
        if (ids.isEmpty()) emptyList() else bindings.visible(jpa.findAllById(ids).map { it.toDomain() })

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

internal fun CpeDeviceJpaEntity.toDomain(): CpeDevice = CpeDevice.rehydrate(
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
