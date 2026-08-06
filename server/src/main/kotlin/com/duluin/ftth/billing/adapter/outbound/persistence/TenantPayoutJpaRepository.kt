package com.duluin.ftth.billing.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// RLS + @TenantId menyaring findAll ke tenant aktif. Riwayat per-tenant, terbaru-dahulu di adapter.
interface TenantPayoutJpaRepository : JpaRepository<TenantPayoutJpaEntity, UUID> {
    fun findFirstByPivotRef(pivotRef: String): TenantPayoutJpaEntity?
}
