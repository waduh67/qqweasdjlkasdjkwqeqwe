package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import java.util.UUID

/**
 * Penyimpanan sub-account Pivot per-tenant. Satu baris per tenant; [find] mengambil baris hasil
 * saring RLS untuk tenant aktif. [findByTenant] dipakai auto-provisioning saat onboarding (di luar
 * konteks tenant biasa — dijalankan dalam `TenantContext.runAs`).
 */
interface TenantPivotAccountRepository {
    fun find(): TenantPivotAccount?
    fun save(account: TenantPivotAccount): TenantPivotAccount
    fun findByTenant(tenantId: UUID): TenantPivotAccount?
}
