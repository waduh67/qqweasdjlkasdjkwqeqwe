package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.BillingTaxSettingsRepository
import com.duluin.ftth.billing.domain.model.BillingTaxSettings
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component

/**
 * Adapter setelan pajak tenant. Satu baris per tenant — [find] mengambil baris tunggal hasil
 * saring RLS; [save] upsert (update baris ada, else insert). Tanpa secret, jadi tak ada batas
 * enkripsi seperti pada setelan notifikasi.
 */
@Component
class BillingTaxSettingsPersistenceAdapter(
    private val jpa: BillingTaxSettingsJpaRepository,
) : BillingTaxSettingsRepository {

    override fun find(): BillingTaxSettings? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(settings: BillingTaxSettings): BillingTaxSettings {
        val entity = jpa.findById(settings.id).orElse(null)?.apply {
            ppnEnabled = settings.ppnEnabled
            ppnRate = settings.ppnRate
            regulatoryEnabled = settings.regulatoryEnabled
            bhpRate = settings.bhpRate
            usoRate = settings.usoRate
        } ?: BillingTaxSettingsJpaEntity(
            id = settings.id,
            ppnEnabled = settings.ppnEnabled,
            ppnRate = settings.ppnRate,
            regulatoryEnabled = settings.regulatoryEnabled,
            bhpRate = settings.bhpRate,
            usoRate = settings.usoRate,
        )
        return jpa.save(entity).toDomain()
    }

    private fun BillingTaxSettingsJpaEntity.toDomain(): BillingTaxSettings = BillingTaxSettings.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        ppnEnabled = ppnEnabled,
        ppnRate = ppnRate,
        regulatoryEnabled = regulatoryEnabled,
        bhpRate = bhpRate,
        usoRate = usoRate,
    )
}
