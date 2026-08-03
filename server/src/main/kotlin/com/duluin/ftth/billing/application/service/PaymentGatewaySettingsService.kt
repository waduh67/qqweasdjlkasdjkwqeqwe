package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.PaymentGatewaySettingsView
import com.duluin.ftth.billing.application.port.inbound.PaywuzMethodView
import com.duluin.ftth.billing.application.port.inbound.UpdatePaymentGatewaySettingsCommand
import com.duluin.ftth.billing.application.port.outbound.PaywuzMethodDirectory
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sisi operator setelan payment gateway. Perubahan dicatat ke jejak audit: mengganti
 * penyedia/kredensial menentukan ke mana uang pelanggan mengalir, jadi harus jelas siapa
 * & kapan mengubahnya.
 */
@Service
@Transactional(readOnly = true)
class PaymentGatewaySettingsService(
    private val repository: TenantPaymentGatewayRepository,
    private val paywuzMethods: PaywuzMethodDirectory,
    private val auditor: AuditRecorder,
) : ManagePaymentGatewaySettingsUseCase {

    override fun get(): PaymentGatewaySettingsView =
        (repository.find() ?: TenantPaymentGateway.defaultFor(TenantContext.tenantId())).toView()

    @Transactional
    override fun update(command: UpdatePaymentGatewaySettingsCommand): PaymentGatewaySettingsView {
        val settings = repository.find() ?: TenantPaymentGateway.defaultFor(TenantContext.tenantId())
        settings.update(
            provider = command.provider,
            mode = command.mode,
            enabled = command.enabled,
            apiKey = command.apiKey,
            secretKey = command.secretKey,
            webhookToken = command.webhookToken,
            paymentMethod = command.paymentMethod,
        )
        val saved = repository.save(settings)
        auditor.record(
            action = "billing.gateway.updated",
            entityType = "TenantPaymentGateway",
            entityId = saved.id,
            tenantId = saved.tenantId,
        )
        return saved.toView()
    }

    override fun listPaywuzMethods(): List<PaywuzMethodView> {
        val gateway = repository.find() ?: return emptyList()
        if (gateway.provider != PaymentProvider.PAYWUZ) return emptyList()
        val apiKey = gateway.apiKey?.takeIf { it.isNotBlank() }
            ?: throw ValidationException("Isi & simpan API key Paywuz dulu untuk memuat daftar metode")
        return paywuzMethods.listMethods(apiKey).map { PaywuzMethodView(it.code, it.name, it.type) }
    }

    private fun TenantPaymentGateway.toView() = PaymentGatewaySettingsView(
        provider = provider.name,
        mode = mode.name,
        enabled = enabled,
        apiKeySet = !apiKey.isNullOrBlank(),
        secretKeySet = !secretKey.isNullOrBlank(),
        webhookTokenSet = !webhookToken.isNullOrBlank(),
        subAccountId = subAccountId,
        paymentMethod = paymentMethod,
    )
}
