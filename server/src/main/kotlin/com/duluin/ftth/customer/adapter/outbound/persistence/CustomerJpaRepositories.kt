package com.duluin.ftth.customer.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** Jumlah ONU terpasang per ODP — proyeksi beralias agar tidak bergantung urutan kolom. */
interface OdpOccupancyCount {
    val odpId: UUID
    val total: Long
}

interface CustomerJpaRepository :
    JpaRepository<CustomerJpaEntity, UUID>,
    JpaSpecificationExecutor<CustomerJpaEntity> {
    fun existsByCode(code: String): Boolean
}

interface SubscriptionJpaRepository : JpaRepository<SubscriptionJpaEntity, UUID> {
    fun findByCustomerIdOrderByCreatedAtDesc(customerId: UUID): List<SubscriptionJpaEntity>
    fun findByCustomerIdIn(customerIds: Collection<UUID>): List<SubscriptionJpaEntity>
}

interface OnuJpaRepository : JpaRepository<OnuJpaEntity, UUID> {
    fun findByCustomerIdOrderBySerialNumber(customerId: UUID): List<OnuJpaEntity>
    fun findByCustomerIdIn(customerIds: Collection<UUID>): List<OnuJpaEntity>
    fun findByOdpIdOrderByOdpPortNumber(odpId: UUID): List<OnuJpaEntity>
    fun existsBySerialNumber(serialNumber: String): Boolean
    fun findBySerialNumberIn(serialNumbers: Collection<String>): List<OnuJpaEntity>

    @Query(
        """
        select o.odpId as odpId, count(o) as total from OnuJpaEntity o
        where o.odpId in :odpIds group by o.odpId
        """,
    )
    fun countGroupedByOdp(@Param("odpIds") odpIds: Collection<UUID>): List<OdpOccupancyCount>
}
