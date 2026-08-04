package com.duluin.ftth.platformbilling.application.port.inbound

import java.math.BigDecimal
import java.util.UUID

/**
 * Dipanggil saat onboarding tenant baru (modul iam) untuk langsung mengaktifkan langganan SaaS-nya.
 * Harga = [monthlyFeeOverride] bila diisi super-admin, jika null memakai harga default global
 * ([com.duluin.ftth.platformbilling.domain.model.PlatformSetting.defaultMonthlyFee]).
 *
 * Idempotent: tenant yang sudah punya langganan dilewati (tak menimpa harga yang berjalan).
 */
interface ProvisionTenantSubscriptionUseCase {
    fun ensureForTenant(tenantId: UUID, monthlyFeeOverride: BigDecimal?)
}
