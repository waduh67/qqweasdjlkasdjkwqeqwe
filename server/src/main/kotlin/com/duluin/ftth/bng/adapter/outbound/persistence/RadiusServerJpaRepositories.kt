package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface RadiusServerJpaRepository : JpaRepository<RadiusServerJpaEntity, UUID> {
    fun existsByName(name: String): Boolean
    fun countByStatus(status: RadiusServerStatus): Long
}

@Repository
interface TenantRadiusAssignmentJpaRepository : JpaRepository<TenantRadiusAssignmentJpaEntity, UUID> {
    fun findByTenantId(tenantId: UUID): TenantRadiusAssignmentJpaEntity?
    fun countByRadiusServerId(radiusServerId: UUID): Int
    fun findByRadiusServerId(radiusServerId: UUID): List<TenantRadiusAssignmentJpaEntity>
    fun deleteByTenantId(tenantId: UUID)

    @Query("SELECT a.radiusServerId, count(a) FROM TenantRadiusAssignmentJpaEntity a GROUP BY a.radiusServerId")
    fun countGroupedByRadiusServerId(): List<Array<Any>>
}
