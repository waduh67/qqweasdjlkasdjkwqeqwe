package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.platformbilling.application.port.inbound.ManagePlatformBillingSettingsUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.PlatformBillingSettingsView
import com.duluin.ftth.platformbilling.application.port.inbound.PlatformGatewayView
import com.duluin.ftth.platformbilling.application.port.inbound.UpdatePlatformGatewayCommand
import com.duluin.ftth.platformbilling.application.port.inbound.UpdatePlatformSettingsCommand
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformPaymentGatewayRepository
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformSettingRepository
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentGateway
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider
import com.duluin.ftth.platformbilling.domain.model.PlatformSetting
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sisi super-admin setelan billing platform. Mengganti gateway aktif/kredensial menentukan ke
 * mana uang langganan tenant mengalir → tiap perubahan dicatat ke jejak audit (tenant platform).
 */
@Service
@Transactional(readOnly = true)
class PlatformBillingSettingsService(
    private val settingRepository: PlatformSettingRepository,
    private val gatewayRepository: PlatformPaymentGatewayRepository,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) : ManagePlatformBillingSettingsUseCase {

    override fun get(): PlatformBillingSettingsView = buildView()

    @Transactional
    override fun updateSetting(command: UpdatePlatformSettingsCommand): PlatformBillingSettingsView {
        val setting = settingRepository.find() ?: PlatformSetting.default()
        setting.update(
            activeProvider = command.activeProvider,
            defaultGraceDays = command.defaultGraceDays,
            defaultDueDays = command.defaultDueDays,
            defaultBillingDay = command.defaultBillingDay,
            currency = command.currency,
        )
        val saved = settingRepository.save(setting)
        auditor.record(
            action = "platform.billing.setting.updated",
            entityType = "PlatformSetting",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("activeProvider" to saved.activeProvider.name),
        )
        return buildView()
    }

    @Transactional
    override fun updateGateway(command: UpdatePlatformGatewayCommand): PlatformBillingSettingsView {
        val gateway = gatewayRepository.findByProvider(command.provider)
            ?: PlatformPaymentGateway.defaultFor(command.provider)
        gateway.update(
            enabled = command.enabled,
            apiKey = command.apiKey,
            secretKey = command.secretKey,
            webhookToken = command.webhookToken,
            paymentMethod = command.paymentMethod,
        )
        val saved = gatewayRepository.save(gateway)
        auditor.record(
            action = "platform.billing.gateway.updated",
            entityType = "PlatformPaymentGateway",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("provider" to saved.provider.name, "enabled" to saved.enabled),
        )
        return buildView()
    }

    private fun buildView(): PlatformBillingSettingsView {
        val setting = settingRepository.find() ?: PlatformSetting.default()
        val byProvider = gatewayRepository.findAll().associateBy { it.provider }
        // Selalu tampilkan SEMUA penyedia (yang belum dikonfigurasi tampil sebagai default MATI).
        val gateways = PlatformPaymentProvider.entries.map { provider ->
            (byProvider[provider] ?: PlatformPaymentGateway.defaultFor(provider)).toView()
        }
        return PlatformBillingSettingsView(
            activeProvider = setting.activeProvider,
            defaultGraceDays = setting.defaultGraceDays,
            defaultDueDays = setting.defaultDueDays,
            defaultBillingDay = setting.defaultBillingDay,
            currency = setting.currency,
            gateways = gateways,
        )
    }

    private fun PlatformPaymentGateway.toView() = PlatformGatewayView(
        provider = provider,
        enabled = enabled,
        apiKeySet = !apiKey.isNullOrBlank(),
        secretKeySet = !secretKey.isNullOrBlank(),
        webhookTokenSet = !webhookToken.isNullOrBlank(),
        paymentMethod = paymentMethod,
        credentialsSet = credentialsSet,
    )
}
