package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.domain.model.ManualPaymentConfig
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Component

/**
 * Adapter setelan penagihan per-tenant. Satu baris per tenant — [find] mengambil baris tunggal
 * hasil saring RLS. Tak ada lagi enkripsi di sini (kredensial gateway dibuang; yang tersisa murni
 * non-rahasia: metode aktif + konfigurasi manual).
 */
@Component
class TenantPaymentGatewayPersistenceAdapter(
    private val jpa: TenantPaymentGatewayJpaRepository,
) : TenantPaymentGatewayRepository {

    override fun find(): TenantPaymentGateway? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(settings: TenantPaymentGateway): TenantPaymentGateway {
        val entity = jpa.findById(settings.id).orElse(null)?.apply {
            provider = settings.provider
            enabled = settings.enabled
            manualTransferEnabled = settings.manual.transferEnabled
            transferBankName = settings.manual.bankName
            transferAccountNumber = settings.manual.accountNumber
            transferAccountHolder = settings.manual.accountHolder
            manualQrisEnabled = settings.manual.qrisEnabled
            qrisStorageKey = settings.qrisStorageKey
            qrisContentType = settings.qrisContentType
        } ?: TenantPaymentGatewayJpaEntity(
            id = settings.id,
            provider = settings.provider,
            enabled = settings.enabled,
            manualTransferEnabled = settings.manual.transferEnabled,
            transferBankName = settings.manual.bankName,
            transferAccountNumber = settings.manual.accountNumber,
            transferAccountHolder = settings.manual.accountHolder,
            manualQrisEnabled = settings.manual.qrisEnabled,
            qrisStorageKey = settings.qrisStorageKey,
            qrisContentType = settings.qrisContentType,
        )
        return jpa.save(entity).toDomain()
    }

    private fun TenantPaymentGatewayJpaEntity.toDomain(): TenantPaymentGateway = TenantPaymentGateway.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        provider = provider,
        enabled = enabled,
        manual = ManualPaymentConfig(
            transferEnabled = manualTransferEnabled,
            bankName = transferBankName,
            accountNumber = transferAccountNumber,
            accountHolder = transferAccountHolder,
            qrisEnabled = manualQrisEnabled,
        ),
        qrisStorageKey = qrisStorageKey,
        qrisContentType = qrisContentType,
    )
}
