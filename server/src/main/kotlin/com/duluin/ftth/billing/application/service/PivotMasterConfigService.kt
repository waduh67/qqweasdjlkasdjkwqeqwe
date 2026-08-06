package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ManagePivotMasterConfigUseCase
import com.duluin.ftth.billing.application.port.inbound.PivotMasterConfigView
import com.duluin.ftth.billing.application.port.inbound.UpdatePivotMasterConfigCommand
import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.billing.domain.model.SubAccountDefaults
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sisi super-admin setelan akun MASTER Pivot. Mengubah kredensial master menentukan ke mana SEMUA
 * uang (pelanggan tenant + langganan SaaS) mengalir → tiap perubahan dicatat ke jejak audit (tenant
 * platform). Enkripsi kredensial terjadi di persistence adapter — service hanya menyerahkan plaintext.
 */
@Service
@Transactional(readOnly = true)
class PivotMasterConfigService(
    private val repository: PivotMasterConfigRepository,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) : ManagePivotMasterConfigUseCase {

    override fun get(): PivotMasterConfigView = (repository.find() ?: PivotMasterConfig.default()).toView()

    @Transactional
    override fun update(command: UpdatePivotMasterConfigCommand): PivotMasterConfigView {
        val config = repository.find() ?: PivotMasterConfig.default()
        config.update(
            enabled = command.enabled,
            merchantId = command.merchantId,
            merchantSecret = command.merchantSecret,
            callbackApiKey = command.callbackApiKey,
            sandbox = command.sandbox,
            platformFeeMinor = command.platformFeeMinor,
            platformFeeType = command.platformFeeType,
            payoutChannelCode = command.payoutChannelCode,
            payoutAccountNumber = command.payoutAccountNumber,
            subAccountDefaults = SubAccountDefaults(
                businessType = command.defaultBusinessType,
                businessStructure = command.defaultBusinessStructure,
                parentIndustry = command.defaultParentIndustry,
                childIndustry = command.defaultChildIndustry,
                mcc = command.defaultMcc,
                digitalStatus = command.defaultDigitalStatus,
                businessCountry = command.defaultBusinessCountry,
                countryOfEntity = command.defaultCountryOfEntity,
                logoUrl = command.defaultLogoUrl,
                website = command.defaultWebsite,
                districtId = command.defaultDistrictId,
                postCode = command.defaultPostCode,
            ),
        )
        val saved = repository.save(config)
        auditor.record(
            action = "platform.pivot.config.updated",
            entityType = "PivotMasterConfig",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("enabled" to saved.enabled, "sandbox" to saved.sandbox),
        )
        return saved.toView()
    }

    private fun PivotMasterConfig.toView() = PivotMasterConfigView(
        enabled = enabled,
        sandbox = sandbox,
        merchantIdSet = !merchantId.isNullOrBlank(),
        merchantSecretSet = !merchantSecret.isNullOrBlank(),
        callbackApiKeySet = !callbackApiKey.isNullOrBlank(),
        credentialsSet = credentialsSet,
        platformFeeMinor = platformFeeMinor,
        platformFeeType = platformFeeType,
        payoutChannelCode = payoutChannelCode,
        payoutAccountNumber = payoutAccountNumber,
        defaultBusinessType = subAccountDefaults.businessType,
        defaultBusinessStructure = subAccountDefaults.businessStructure,
        defaultParentIndustry = subAccountDefaults.parentIndustry,
        defaultChildIndustry = subAccountDefaults.childIndustry,
        defaultMcc = subAccountDefaults.mcc,
        defaultDigitalStatus = subAccountDefaults.digitalStatus,
        defaultBusinessCountry = subAccountDefaults.businessCountry,
        defaultCountryOfEntity = subAccountDefaults.countryOfEntity,
        defaultLogoUrl = subAccountDefaults.logoUrl,
        defaultWebsite = subAccountDefaults.website,
        defaultDistrictId = subAccountDefaults.districtId,
        defaultPostCode = subAccountDefaults.postCode,
    )
}
