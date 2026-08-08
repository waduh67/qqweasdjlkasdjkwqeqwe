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

    @Column(name = "payout_fee_minor", nullable = false)
    var payoutFeeMinor: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_fee_type", nullable = false, length = 20)
    var payoutFeeType: PivotFeeType,

    @Column(name = "payout_channel_code", length = 40)
    var payoutChannelCode: String?,

    @Column(name = "payout_account_number", length = 60)
    var payoutAccountNumber: String?,

    // Default field wajib create sub-account (non-rahasia → plaintext).
    @Column(name = "default_business_type", length = 40)
    var defaultBusinessType: String?,

    @Column(name = "default_business_structure", length = 40)
    var defaultBusinessStructure: String?,

    @Column(name = "default_parent_industry", length = 120)
    var defaultParentIndustry: String?,

    @Column(name = "default_child_industry", length = 120)
    var defaultChildIndustry: String?,

    @Column(name = "default_mcc", length = 20)
    var defaultMcc: String?,

    @Column(name = "default_digital_status", length = 40)
    var defaultDigitalStatus: String?,

    @Column(name = "default_business_country", length = 8)
    var defaultBusinessCountry: String?,

    @Column(name = "default_country_of_entity", length = 8)
    var defaultCountryOfEntity: String?,

    @Column(name = "default_logo_url", length = 500)
    var defaultLogoUrl: String?,

    @Column(name = "default_website", length = 300)
    var defaultWebsite: String?,

    @Column(name = "default_district_id")
    var defaultDistrictId: Int?,

    @Column(name = "default_post_code", length = 20)
    var defaultPostCode: String?,
) : BaseJpaEntity(id)
