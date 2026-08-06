package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.PivotFeeType
import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Setelan MASTER Pivot platform (singleton). Tabel PLATFORM-level (tanpa RLS, pola
 * `platform_setting`). [merchantId]/[merchantSecret]/[callbackApiKey] menyimpan CIPHERTEXT —
 * enkripsi terjadi di adapter, DB tak pernah melihat rahasia asli.
 */
@Entity
@Table(name = "pivot_master_config")
class PivotMasterConfigJpaEntity(
    id: UUID,

    @Column(nullable = false)
    var enabled: Boolean,

    @Column(name = "merchant_id", length = 1024)
    var merchantId: String?,

    @Column(name = "merchant_secret", length = 1024)
    var merchantSecret: String?,

    @Column(name = "callback_api_key", length = 1024)
    var callbackApiKey: String?,

    @Column(nullable = false)
    var sandbox: Boolean,

    @Column(name = "platform_fee_minor", nullable = false)
    var platformFeeMinor: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_fee_type", nullable = false, length = 20)
    var platformFeeType: PivotFeeType,

    @Column(name = "payout_channel_code", length = 40)
    var payoutChannelCode: String?,

    @Column(name = "payout_account_number", length = 60)
    var payoutAccountNumber: String?,
) : BaseJpaEntity(id)
