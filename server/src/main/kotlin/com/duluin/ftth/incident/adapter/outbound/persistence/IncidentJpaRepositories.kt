package com.duluin.ftth.incident.adapter.outbound.persistence

import com.duluin.ftth.incident.domain.model.IncidentStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface IncidentJpaRepository : JpaRepository<IncidentJpaEntity, UUID> {
    fun findByStatusNot(status: IncidentStatus): List<IncidentJpaEntity>
}

interface IncidentEventJpaRepository : JpaRepository<IncidentEventJpaEntity, UUID> {
    fun findByIncidentIdOrderByAt(incidentId: UUID): List<IncidentEventJpaEntity>
}
