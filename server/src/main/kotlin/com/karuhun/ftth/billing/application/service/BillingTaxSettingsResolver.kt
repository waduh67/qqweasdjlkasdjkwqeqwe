package com.karuhun.ftth.billing.application.service

import com.karuhun.ftth.billing.application.port.outbound.BillingTaxSettingsRepository
import com.karuhun.ftth.billing.domain.model.BillingTaxSettings
import com.karuhun.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component

/**
 * Menyelesaikan setelan pajak tenant aktif: baris config (via RLS) bila ada, else bawaan
 * [BillingTaxSettings.defaultFor] (kedua fitur mati). Meniru [TenantPaymentGatewayResolver] —
 * satu tempat resolusi, dipanggil sekali per ronde penerbitan tagihan maupun saat menghitung
 * kewajiban BHP/USO.
 */
@Component
class BillingTaxSettingsResolver(
    private val repository: BillingTaxSettingsRepository,
) {
    fun resolve(): BillingTaxSettings =
        repository.find() ?: BillingTaxSettings.defaultFor(TenantContext.tenantId())
}
