package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.application.port.outbound.DiscoveredOnuRepository
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class DiscoveredOnuPersistenceAdapter(
    private val jpa: DiscoveredOnuJpaRepository,
) : DiscoveredOnuRepository {

    override fun save(discovered: DiscoveredOnu): DiscoveredOnu {
        // Serial dan waktu pertama-terlihat tak berubah; hanya pengamatan terakhir & tahap.
        val entity = jpa.findById(discovered.id).orElse(null)?.apply {
            oltId = discovered.oltId
            oltCode = discovered.oltCode
            ponPortLabel = discovered.ponPortLabel
            lastStatus = discovered.lastStatus
            lastRxPowerDbm = discovered.lastRxPowerDbm
            lastSeenAt = discovered.lastSeenAt
            seenCount = discovered.seenCount
            state = discovered.state
        } ?: DiscoveredOnuJpaEntity(
            id = discovered.id,
            serialNumber = discovered.serialNumber,
            oltId = discovered.oltId,
            oltCode = discovered.oltCode,
            ponPortLabel = discovered.ponPortLabel,
            lastStatus = discovered.lastStatus,
            lastRxPowerDbm = discovered.lastRxPowerDbm,
            firstSeenAt = discovered.firstSeenAt,
            lastSeenAt = discovered.lastSeenAt,
            seenCount = discovered.seenCount,
            state = discovered.state,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): DiscoveredOnu? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findBySerialNumber(serialNumber: String): DiscoveredOnu? =
        jpa.findBySerialNumber(serialNumber.trim().uppercase())?.toDomain()

    override fun findByState(state: DiscoveredOnuState): List<DiscoveredOnu> =
        jpa.findByStateOrderByLastSeenAtDesc(state).map { it.toDomain() }

    override fun findByStateAndOltId(state: DiscoveredOnuState, oltId: UUID): List<DiscoveredOnu> =
        jpa.findByStateAndOltIdOrderByLastSeenAtDesc(state, oltId).map { it.toDomain() }

    override fun findDiscoveredBySerials(serialNumbers: Set<String>): List<DiscoveredOnu> =
        if (serialNumbers.isEmpty()) {
            emptyList()
        } else {
            jpa.findBySerialNumberInAndState(serialNumbers, DiscoveredOnuState.DISCOVERED).map { it.toDomain() }
        }

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    override fun deleteByOltId(oltId: UUID): Int = jpa.deleteByOltId(oltId)
}

private fun DiscoveredOnuJpaEntity.toDomain(): DiscoveredOnu = DiscoveredOnu.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    serialNumber = serialNumber,
    oltId = oltId,
    oltCode = oltCode,
    ponPortLabel = ponPortLabel,
    lastStatus = lastStatus,
    lastRxPowerDbm = lastRxPowerDbm,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    seenCount = seenCount,
    state = state,
)
