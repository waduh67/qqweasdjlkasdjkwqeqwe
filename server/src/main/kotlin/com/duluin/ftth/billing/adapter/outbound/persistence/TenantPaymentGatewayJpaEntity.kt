package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Satu baris setelan penagihan per tenant. TIDAK menyimpan kredensial apa pun (model BYOK
 * dibuang — charge Pivot memakai akun master + sub-account, lihat [PivotMasterConfigJpaEntity] &
 * [TenantPivotAccountJpaEntity]). Yang tersisa: metode aktif ([provider]) + konfigurasi
 * pembayaran MANUAL (semua non-rahasia, plaintext).
 */
@Entity
@Table(name = "tenant_payment_gateway")
class TenantPaymentGatewayJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: PaymentProvider,

    @Column(nullable = false)
    var enabled: Boolean,

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
