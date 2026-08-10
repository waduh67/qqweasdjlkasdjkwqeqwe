package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.customer.domain.model.CustomerStatus
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Jumlah ONU terpasang per ODP — proyeksi beralias agar tidak bergantung urutan kolom. */
interface OdpOccupancyCount {
    val odpId: UUID
    val total: Long
}

/** Cacah langganan per status — proyeksi beralias untuk laporan. */
interface SubscriptionStatusCount {
    val status: SubscriptionStatus
    val total: Long
}

interface CustomerJpaRepository :
    JpaRepository<CustomerJpaEntity, UUID>,
    JpaSpecificationExecutor<CustomerJpaEntity> {
    fun existsByCode(code: String): Boolean

    /**
     * Pelanggan menunggu instalasi: belum diputus dan tak punya satu pun ONU yang
     * terpasang ke ODP. Sub-kueri NOT EXISTS ke ONU ikut disaring `@TenantId`.
     */
    @Query(
        """
        select c from CustomerJpaEntity c
        where c.status <> :excluded
          and not exists (
            select 1 from OnuJpaEntity o where o.customerId = c.id and o.odpId is not null
          )
        """,
    )
    fun findAwaitingInstallation(@Param("excluded") excluded: CustomerStatus): List<CustomerJpaEntity>

    @Query(
        """
        select c from CustomerJpaEntity c
        where c.status <> :excluded
          and c.areaId in :areaIds
          and not exists (
            select 1 from OnuJpaEntity o where o.customerId = c.id and o.odpId is not null
          )
        """,
    )
    fun findAwaitingInstallationInAreas(
        @Param("excluded") excluded: CustomerStatus,
        @Param("areaIds") areaIds: Collection<UUID>,
    ): List<CustomerJpaEntity>
}

interface SubscriptionJpaRepository : JpaRepository<SubscriptionJpaEntity, UUID> {
    fun findByCustomerIdOrderByCreatedAtDesc(customerId: UUID): List<SubscriptionJpaEntity>
    fun findByCustomerIdIn(customerIds: Collection<UUID>): List<SubscriptionJpaEntity>
    fun findByStatusIn(statuses: Collection<SubscriptionStatus>): List<SubscriptionJpaEntity>

    @Query("select s.status as status, count(s) as total from SubscriptionJpaEntity s group by s.status")
    fun countGroupedByStatus(): List<SubscriptionStatusCount>

    /** Jumlah tarif bulanan langganan pada status tertentu (MRR = ACTIVE+ISOLATED). */
    @Query("select coalesce(sum(s.monthlyFee), 0) from SubscriptionJpaEntity s where s.status in :statuses")
    fun sumMonthlyFeeByStatusIn(@Param("statuses") statuses: Collection<SubscriptionStatus>): BigDecimal

    fun countByActivatedAtGreaterThanEqualAndActivatedAtLessThan(from: Instant, toExclusive: Instant): Long

    fun countByTerminatedAtGreaterThanEqualAndTerminatedAtLessThan(from: Instant, toExclusive: Instant): Long

    /** Hidup pada [at] = sudah teraktivasi (≤ at) dan belum diakhiri (null atau > at). */
    @Query(
        """
        select count(s) from SubscriptionJpaEntity s
        where s.activatedAt is not null and s.activatedAt <= :at
          and (s.terminatedAt is null or s.terminatedAt > :at)
        """,
    )
    fun countLiveAt(@Param("at") at: Instant): Long
}

interface OnuJpaRepository : JpaRepository<OnuJpaEntity, UUID> {
    fun findByCustomerIdOrderBySerialNumber(customerId: UUID): List<OnuJpaEntity>
    fun findByCustomerIdIn(customerIds: Collection<UUID>): List<OnuJpaEntity>
    fun findByOdpIdOrderByOdpPortNumber(odpId: UUID): List<OnuJpaEntity>

    /** ONU terpasang di sekumpulan ODP sekaligus (satu query IN) — untuk pandangan per-OLT. */
    fun findByOdpIdInOrderByOdpPortNumber(odpIds: Collection<UUID>): List<OnuJpaEntity>
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
