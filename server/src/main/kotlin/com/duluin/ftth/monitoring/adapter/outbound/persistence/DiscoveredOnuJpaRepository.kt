package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DiscoveredOnuJpaRepository : JpaRepository<DiscoveredOnuJpaEntity, UUID> {

    fun findBySerialNumber(serialNumber: String): DiscoveredOnuJpaEntity?

    fun findByStateOrderByLastSeenAtDesc(state: DiscoveredOnuState): List<DiscoveredOnuJpaEntity>

    fun findBySerialNumberInAndState(
        serialNumbers: Set<String>,
        state: DiscoveredOnuState,
    ): List<DiscoveredOnuJpaEntity>
}
