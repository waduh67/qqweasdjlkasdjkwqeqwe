package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.monitoring.domain.model.AlarmStatus
import com.duluin.ftth.monitoring.domain.model.CollectorStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** Jumlah alarm per status, untuk ringkasan dashboard tanpa menarik barisnya. */
interface AlarmStatusCount {
    val status: AlarmStatus
    val total: Long
}

interface CollectorJpaRepository : JpaRepository<CollectorJpaEntity, UUID> {
    fun findByApiKeyHash(apiKeyHash: String): CollectorJpaEntity?
    fun findByTenantIdOrderByName(tenantId: UUID): List<CollectorJpaEntity>
    fun findByStatus(status: CollectorStatus): List<CollectorJpaEntity>
    fun existsByTenantIdAndName(tenantId: UUID, name: String): Boolean
}

interface AlarmJpaRepository : JpaRepository<AlarmJpaEntity, UUID>, JpaSpecificationExecutor<AlarmJpaEntity> {

    fun findByKindAndEntityIdAndStatusNot(
        kind: AlarmKind,
        entityId: UUID,
        status: AlarmStatus,
    ): AlarmJpaEntity?

    fun findByKindAndStatusNot(kind: AlarmKind, status: AlarmStatus): List<AlarmJpaEntity>

    fun findByStatusNot(status: AlarmStatus): List<AlarmJpaEntity>

    @Query("select a.status as status, count(a) as total from AlarmJpaEntity a group by a.status")
    fun countGroupedByStatus(): List<AlarmStatusCount>
}

interface AlarmRuleJpaRepository : JpaRepository<AlarmRuleJpaEntity, UUID> {
    fun findByKind(kind: AlarmKind): AlarmRuleJpaEntity?
}
