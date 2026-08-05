package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.customer.domain.model.CustomerStatus
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
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
     * Nomor urut tertinggi di antara kode berbentuk `{prefix}{angka}` (mis. `CUST-000042` → 42),
     * atau 0. Native Postgres agar bisa memangkas prefiks & membandingkan sebagai integer; RLS
     * per-koneksi otomatis membatasi ke tenant aktif.
     */
    @Query(
        value = """
            select coalesce(max(cast(substring(code from '^' || :prefix || '([0-9]+)$') as integer)), 0)
            from customer
            where code ~ ('^' || :prefix || '[0-9]+$')
        """,
        nativeQuery = true,
    )
    fun maxCodeSequence(@Param("prefix") prefix: String): Int

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
