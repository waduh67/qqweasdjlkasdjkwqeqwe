package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Kredensial satu penyedia pembayaran platform (satu baris per penyedia). Tabel platform-level
 * (tanpa RLS). [apiKey]/[secretKey]/[webhookToken] menyimpan CIPHERTEXT — enkripsi di adapter.
 */
@Entity
@Table(name = "platform_payment_gateway")
class PlatformPaymentGatewayJpaEntity(
    id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var provider: PlatformPaymentProvider,

    @Column(nullable = false)
    var enabled: Boolean,

    @Column(name = "api_key", length = 1024)
    var apiKey: String?,

    @Column(name = "secret_key", length = 1024)
    var secretKey: String?,

    @Column(name = "webhook_token", length = 1024)
    var webhookToken: String?,

    // BUKAN ciphertext — kode metode Paywuz (bukan rahasia).
    @Column(name = "payment_method", length = 64)
    var paymentMethod: String?,
) : BaseJpaEntity(id)
