package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.application.port.outbound.PlatformSettingRepository
import com.duluin.ftth.platformbilling.domain.model.PlatformSetting
import org.springframework.stereotype.Component

/**
 * Adapter setelan billing global (singleton). Baris tunggal — [find] mengambil baris pertama
 * (tabel hanya pernah berisi satu). Bukan tenant-aware (platform-level, tanpa RLS).
 */
@Component
class PlatformSettingPersistenceAdapter(
    private val jpa: PlatformSettingJpaRepository,
) : PlatformSettingRepository {

    override fun find(): PlatformSetting? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(setting: PlatformSetting): PlatformSetting {
        val entity = jpa.findById(setting.id).orElse(null)?.apply {
            activeProvider = setting.activeProvider
            defaultGraceDays = setting.defaultGraceDays
            defaultDueDays = setting.defaultDueDays
            defaultBillingDay = setting.defaultBillingDay
            currency = setting.currency
        } ?: PlatformSettingJpaEntity(
            id = setting.id,
            activeProvider = setting.activeProvider,
            defaultGraceDays = setting.defaultGraceDays,
            defaultDueDays = setting.defaultDueDays,
            defaultBillingDay = setting.defaultBillingDay,
            currency = setting.currency,
        )
        return jpa.save(entity).toDomain()
    }

    private fun PlatformSettingJpaEntity.toDomain(): PlatformSetting = PlatformSetting.rehydrate(
        id = id,
        activeProvider = activeProvider,
        defaultGraceDays = defaultGraceDays,
        defaultDueDays = defaultDueDays,
        defaultBillingDay = defaultBillingDay,
        currency = currency,
    )
}
