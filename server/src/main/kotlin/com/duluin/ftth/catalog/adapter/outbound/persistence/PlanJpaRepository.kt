package com.duluin.ftth.catalog.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlanJpaRepository : JpaRepository<PlanJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<PlanJpaEntity>
    fun existsByName(name: String): Boolean
}
