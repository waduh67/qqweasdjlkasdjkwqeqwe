package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.TenantPivotAccountRepository
import com.duluin.ftth.billing.domain.model.TenantPivotAccount
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Adapter sub-account Pivot per-tenant. Satu baris per tenant — [find] mengambil baris tunggal
 * hasil saring RLS. [findByTenant] dipakai auto-provisioning saat onboarding (dijalankan dalam
 * `TenantContext.runAs(tenantId)`, jadi RLS sudah menyaring ke tenant sasaran).
 */
@Component
class TenantPivotAccountPersistenceAdapter(
    private val jpa: TenantPivotAccountJpaRepository,
) : TenantPivotAccountRepository {

    override fun find(): TenantPivotAccount? = jpa.findAll().firstOrNull()?.toDomain()

    override fun findByTenant(tenantId: UUID): TenantPivotAccount? =
        jpa.findAll().firstOrNull { it.tenantId == tenantId }?.toDomain()

    override fun save(account: TenantPivotAccount): TenantPivotAccount {
        val entity = jpa.findById(account.id).orElse(null)?.apply {
            subMerchantUuid = account.subMerchantUuid
            type = account.type
            status = account.status
            kycStatus = account.kycStatus
            shortName = account.shortName
            legalName = account.legalName
            merchantEmail = account.merchantEmail
            merchantPhone = account.merchantPhone
            picName = account.picName
            picEmail = account.picEmail
            picPhone = account.picPhone
            address = account.address
            payoutChannelCode = account.payoutChannelCode
            payoutAccountNumber = account.payoutAccountNumber
            payoutAccountName = account.payoutAccountName
            payoutInquiryId = account.payoutInquiryId
        } ?: TenantPivotAccountJpaEntity(
            id = account.id,
            subMerchantUuid = account.subMerchantUuid,
            type = account.type,
            status = account.status,
            kycStatus = account.kycStatus,
            shortName = account.shortName,
            legalName = account.legalName,
            merchantEmail = account.merchantEmail,
            merchantPhone = account.merchantPhone,
            picName = account.picName,
            picEmail = account.picEmail,
            picPhone = account.picPhone,
            address = account.address,
            payoutChannelCode = account.payoutChannelCode,
            payoutAccountNumber = account.payoutAccountNumber,
            payoutAccountName = account.payoutAccountName,
            payoutInquiryId = account.payoutInquiryId,
        )
        return jpa.save(entity).toDomain()
    }

    private fun TenantPivotAccountJpaEntity.toDomain(): TenantPivotAccount = TenantPivotAccount.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        subMerchantUuid = subMerchantUuid,
        type = type,
        status = status,
        kycStatus = kycStatus,
        shortName = shortName,
        legalName = legalName,
        merchantEmail = merchantEmail,
        merchantPhone = merchantPhone,
        picName = picName,
        picEmail = picEmail,
        picPhone = picPhone,
        address = address,
        payoutChannelCode = payoutChannelCode,
        payoutAccountNumber = payoutAccountNumber,
        payoutAccountName = payoutAccountName,
        payoutInquiryId = payoutInquiryId,
    )
}
