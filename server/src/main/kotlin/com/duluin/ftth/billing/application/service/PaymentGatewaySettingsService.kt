package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.ManualPaymentInstructionsView
import com.duluin.ftth.billing.application.port.inbound.PaymentGatewaySettingsView
import com.duluin.ftth.billing.application.port.inbound.UpdatePaymentGatewaySettingsCommand
import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.domain.model.ManualPaymentConfig
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Sisi operator setelan payment gateway. Perubahan dicatat ke jejak audit: mengganti
 * penyedia/kredensial menentukan ke mana uang pelanggan mengalir, jadi harus jelas siapa
 * & kapan mengubahnya.
 */
@Service
@Transactional(readOnly = true)
class PaymentGatewaySettingsService(
    private val repository: TenantPaymentGatewayRepository,
    private val auditor: AuditRecorder,
    private val storage: ObjectStorage,
) : ManagePaymentGatewaySettingsUseCase {

    override fun get(): PaymentGatewaySettingsView =
        (repository.find() ?: TenantPaymentGateway.defaultFor(TenantContext.tenantId())).toView()

    @Transactional
    override fun update(command: UpdatePaymentGatewaySettingsCommand): PaymentGatewaySettingsView {
        val settings = repository.find() ?: TenantPaymentGateway.defaultFor(TenantContext.tenantId())
        val tripay = TripayPaymentConfig(
            merchantCode = command.tripayMerchantCode,
            apiKey = command.tripayApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: settings.tripay.apiKeyForGateway(),
            privateKey = command.tripayPrivateKey?.trim()?.takeIf { it.isNotEmpty() } ?: settings.tripay.privateKeyForGateway(),
            sandbox = command.tripaySandbox,
        ).normalized()
        if (command.provider == PaymentProvider.TRIPAY && command.enabled && !tripay.ready) {
            throw ValidationException(
                "Tripay aktif memerlukan merchant code, API key, dan private key yang lengkap",
            )
        }
        settings.update(
            provider = command.provider,
            enabled = command.enabled,
            manual = ManualPaymentConfig(
                transferEnabled = command.manualTransferEnabled,
                bankName = command.bankName,
                accountNumber = command.accountNumber,
                accountHolder = command.accountHolder,
                qrisEnabled = command.manualQrisEnabled,
            ),
            tripay = tripay,
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

    @Transactional
    override fun uploadQrisImage(contentType: String, bytes: ByteArray): PaymentGatewaySettingsView {
        if (!contentType.startsWith("image/")) {
            throw ValidationException("Gambar QRIS harus berupa berkas gambar")
        }
        if (bytes.size > MAX_QRIS_BYTES) {
            throw ValidationException("Gambar QRIS maksimal ${MAX_QRIS_BYTES / (1024 * 1024)} MB")
        }
        val settings = repository.find() ?: TenantPaymentGateway.defaultFor(TenantContext.tenantId())
        val key = qrisStorageKey(settings.tenantId)
        // Taruh byte ke storage dulu, baru simpan metadata (pola WorkOrderEvidenceService).
        storage.put(key, contentType, bytes)
        settings.attachQrisImage(key, contentType)
        val saved = repository.save(settings)
        auditor.record(
            action = "billing.gateway.qris.uploaded",
            entityType = "TenantPaymentGateway",
            entityId = saved.id,
            tenantId = saved.tenantId,
        )
        return saved.toView()
    }

    @Transactional
    override fun deleteQrisImage(): PaymentGatewaySettingsView {
        val settings = repository.find() ?: TenantPaymentGateway.defaultFor(TenantContext.tenantId())
        settings.qrisStorageKey?.let { storage.delete(it) }
        settings.clearQrisImage()
        val saved = repository.save(settings)
        auditor.record(
            action = "billing.gateway.qris.deleted",
            entityType = "TenantPaymentGateway",
            entityId = saved.id,
            tenantId = saved.tenantId,
        )
        return saved.toView()
    }

    override fun getQrisImage(): StoredObject? {
        val key = repository.find()?.qrisStorageKey?.takeIf { it.isNotBlank() } ?: return null
        return storage.get(key)
    }

    override fun manualPaymentInstructions(): ManualPaymentInstructionsView {
        val settings = repository.find() ?: TenantPaymentGateway.defaultFor(TenantContext.tenantId())
        return ManualPaymentInstructionsView(
            transferEnabled = settings.manual.transferEnabled,
            bankName = settings.manual.bankName,
            accountNumber = settings.manual.accountNumber,
            accountHolder = settings.manual.accountHolder,
            qrisEnabled = settings.manual.qrisEnabled,
            qrisImageAvailable = settings.qrisImageSet,
        )
    }

    private fun TenantPaymentGateway.toView() = PaymentGatewaySettingsView(
        provider = provider.name,
        enabled = enabled,
        manualTransferEnabled = manual.transferEnabled,
        bankName = manual.bankName,
        accountNumber = manual.accountNumber,
        accountHolder = manual.accountHolder,
        manualQrisEnabled = manual.qrisEnabled,
        qrisImageSet = qrisImageSet,
        tripayMerchantCode = tripay.merchantCode,
        tripayApiKeySet = !tripay.apiKeyForGateway().isNullOrBlank(),
        tripayPrivateKeySet = !tripay.privateKeyForGateway().isNullOrBlank(),
        tripaySandbox = tripay.sandbox,
    )

    private companion object {
        const val MAX_QRIS_BYTES = 5 * 1024 * 1024

        /** Satu key per tenant; unggah ulang menimpa. Terprefiks tenant (pola bukti work-order). */
        fun qrisStorageKey(tenantId: UUID) = "$tenantId/billing/gateway/qris"
    }
}
