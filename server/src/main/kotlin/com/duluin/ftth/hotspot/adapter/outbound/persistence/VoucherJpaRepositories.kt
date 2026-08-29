package com.duluin.ftth.hotspot.adapter.outbound.persistence

import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface VoucherJpaRepository : JpaRepository<VoucherJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select voucher from VoucherJpaEntity voucher where voucher.id = :id")
    fun findLockedById(@Param("id") id: UUID): VoucherJpaEntity?

    @Query("""
        select voucher.id from VoucherJpaEntity voucher
        where voucher.status = :status and voucher.expiresAt <= :now
        order by voucher.expiresAt asc
    """)
    fun findIdsByStatusAndExpiresAtBeforeOrEqual(
        @Param("status") status: VoucherStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>

    @Query("""
        select voucher from VoucherJpaEntity voucher
        where (:batchId is null or voucher.batchId = :batchId)
          and (:siteId is null or voucher.siteId = :siteId)
          and (:status is null or voucher.status = :status)
    """)
    fun search(
        @Param("batchId") batchId: UUID?,
        @Param("siteId") siteId: UUID?,
        @Param("status") status: VoucherStatus?,
        pageable: Pageable,
    ): Page<VoucherJpaEntity>
}

interface VoucherBatchJpaRepository : JpaRepository<VoucherBatchJpaEntity, UUID> {
    @Query("select batch from VoucherBatchJpaEntity batch where (:siteId is null or batch.siteId = :siteId)")
    fun search(@Param("siteId") siteId: UUID?, pageable: Pageable): Page<VoucherBatchJpaEntity>
}
