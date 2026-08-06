package com.duluin.ftth.billing.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Satu baris per tenant; RLS + @TenantId menyaring findAll ke tenant aktif.
interface TenantPivotAccountJpaRepository : JpaRepository<TenantPivotAccountJpaEntity, UUID>
