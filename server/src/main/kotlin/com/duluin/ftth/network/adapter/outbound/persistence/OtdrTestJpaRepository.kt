package com.duluin.ftth.network.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OtdrTestJpaRepository : JpaRepository<OtdrTestJpaEntity, UUID> {
    fun findByCableIdOrderByRecordedAtDesc(cableId: UUID): List<OtdrTestJpaEntity>
}
