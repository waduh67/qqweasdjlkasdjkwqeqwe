package com.duluin.ftth.billing.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

// Satu baris per tenant; RLS + @TenantId menyaring findAll ke tenant aktif, jadi
// tak perlu query turunan — adapter ambil baris pertama (satu-satunya) dari findAll.
interface TenantPaymentGatewayJpaRepository : JpaRepository<TenantPaymentGatewayJpaEntity, UUID>
