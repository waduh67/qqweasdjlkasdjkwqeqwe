package com.duluin.ftth.billing.application.port.inbound

import java.util.UUID

/**
 * Provisioning sub-account Pivot untuk tenant, dipisah dari manajemen operator agar bisa dipanggil
 * lintas-tenant (listener onboarding, backfill) tanpa konteks request. Idempotent: aman dipanggil
 * berulang — sub-account yang sudah ada dilewati.
 *
 * Analog `ProvisionTenantSubscriptionUseCase` di platformbilling. Dipakai listener
 * `TenantOnboardedEvent`; berjalan dalam `TenantContext.runAs(tenantId)`.
 */
interface ProvisionTenantPivotAccountUseCase {
    /** Pastikan tenant punya sub-account NON_KYC di Pivot; buat bila belum. No-op bila master Pivot mati. */
    fun ensureForTenant(tenantId: UUID)
}
