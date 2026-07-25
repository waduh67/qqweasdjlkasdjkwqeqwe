package com.duluin.ftth.cpe.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CpeDeviceJpaRepository : JpaRepository<CpeDeviceJpaEntity, UUID> {
    fun findByGenieacsId(genieacsId: String): CpeDeviceJpaEntity?
    fun findByCustomerId(customerId: UUID): List<CpeDeviceJpaEntity>
}

interface CpeActionLogJpaRepository : JpaRepository<CpeActionLogJpaEntity, UUID> {
    fun findByDeviceIdOrderByRequestedAtDesc(deviceId: UUID): List<CpeActionLogJpaEntity>
}
