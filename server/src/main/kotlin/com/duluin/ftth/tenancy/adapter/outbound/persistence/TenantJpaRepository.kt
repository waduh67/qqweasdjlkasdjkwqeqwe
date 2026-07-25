package com.duluin.ftth.tenancy.adapter.outbound.persistence

import com.duluin.ftth.tenancy.TenantStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TenantJpaRepository : JpaRepository<TenantJpaEntity, UUID> {
    fun findBySlug(slug: String): TenantJpaEntity?

    @Query("SELECT t.id FROM TenantJpaEntity t WHERE t.status = :status")
    fun findIdsByStatus(status: TenantStatus): List<UUID>
}
