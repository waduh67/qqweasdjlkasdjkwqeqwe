package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Satu baris setelan payment gateway per tenant. Mutable (setelan disunting berulang).
 * [apiKey]/[secretKey]/[webhookToken] menyimpan CIPHERTEXT — enkripsi terjadi di adapter,
 * DB tak pernah melihat rahasia asli (cermin [NotificationSettingsJpaEntity]).
 */
@Entity
@Table(name = "tenant_payment_gateway")
class TenantPaymentGatewayJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: PaymentProvider,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var mode: GatewayMode,

    @Column(nullable = false)
    var enabled: Boolean,

    @Column(name = "api_key", length = 1024)
    var apiKey: String?,

    @Column(name = "secret_key", length = 1024)
    var secretKey: String?,

    @Column(name = "webhook_token", length = 1024)
    var webhookToken: String?,

    @Column(name = "sub_account_id", length = 128)
    var subAccountId: String?,

    // BUKAN ciphertext — kode metode Paywuz per-tenant (bukan rahasia).
    @Column(name = "payment_method", length = 64)
    var paymentMethod: String?,

    // --- Pembayaran manual (transfer/QRIS) — semua NON-rahasia (plaintext). ---
    @Column(name = "manual_transfer_enabled", nullable = false)
    var manualTransferEnabled: Boolean,

    @Column(name = "transfer_bank_name", length = 120)
    var transferBankName: String?,

    @Column(name = "transfer_account_number", length = 60)
    var transferAccountNumber: String?,

    @Column(name = "transfer_account_holder", length = 160)
    var transferAccountHolder: String?,

    @Column(name = "manual_qris_enabled", nullable = false)
    var manualQrisEnabled: Boolean,

    // Object-storage key gambar QRIS (byte-nya di MinIO/S3, bukan di DB).
    @Column(name = "qris_storage_key", length = 255)
    var qrisStorageKey: String?,

    @Column(name = "qris_content_type", length = 100)
    var qrisContentType: String?,
) : TenantAwareJpaEntity(id)
