package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.platformbilling.application.port.inbound.ManagePlatformBillingSettingsUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.PlatformBillingSettingsView
import com.duluin.ftth.platformbilling.application.port.inbound.UpdatePlatformSettingsCommand
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformSettingRepository
import com.duluin.ftth.platformbilling.domain.model.PlatformSetting
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sisi super-admin setelan billing platform: default grace/jatuh-tempo/tanggal-tagih + harga
 * bulanan bawaan. Perubahan dicatat ke jejak audit (tenant platform). Kredensial pembayaran
 * dikelola terpisah (setelan Pivot platform).
 */
@Service
@Transactional(readOnly = true)
class PlatformBillingSettingsService(
    private val settingRepository: PlatformSettingRepository,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) : ManagePlatformBillingSettingsUseCase {

    override fun get(): PlatformBillingSettingsView = (settingRepository.find() ?: PlatformSetting.default()).toView()

    @Transactional
    override fun updateSetting(command: UpdatePlatformSettingsCommand): PlatformBillingSettingsView {
        val setting = settingRepository.find() ?: PlatformSetting.default()
        setting.update(
            defaultGraceDays = command.defaultGraceDays,
            defaultDueDays = command.defaultDueDays,
            defaultBillingDay = command.defaultBillingDay,
            defaultMonthlyFee = command.defaultMonthlyFee,
            currency = command.currency,
        )
        val saved = settingRepository.save(setting)
        auditor.record(
            action = "platform.billing.setting.updated",
            entityType = "PlatformSetting",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
        )
        return saved.toView()
    }

    private fun PlatformSetting.toView() = PlatformBillingSettingsView(
        defaultGraceDays = defaultGraceDays,
        defaultDueDays = defaultDueDays,
        defaultBillingDay = defaultBillingDay,
        defaultMonthlyFee = defaultMonthlyFee,
        currency = currency,
    )
}
