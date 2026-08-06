package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.domain.model.SubAccountKycStatus
import com.duluin.ftth.billing.domain.model.SubAccountStatus
import com.duluin.ftth.billing.domain.model.SubAccountType
import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Satu baris sub-account Pivot per tenant (tenant-scoped + RLS). Semua kolom NON-rahasia
 * (uuid sub-account, status, rekening payout) — tanpa enkripsi.
 */
@Entity
@Table(name = "tenant_pivot_account")
class TenantPivotAccountJpaEntity(
    id: UUID,

    @Column(name = "sub_merchant_uuid", length = 128)
    var subMerchantUuid: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    var type: SubAccountType,

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_account_status", nullable = false, length = 30)
    var status: SubAccountStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 30)
    var kycStatus: SubAccountKycStatus,

    @Column(name = "short_name", length = 120)
    var shortName: String?,

    @Column(name = "payout_channel_code", length = 40)
    var payoutChannelCode: String?,

    @Column(name = "payout_account_number", length = 60)
    var payoutAccountNumber: String?,

    @Column(name = "payout_account_name", length = 160)
    var payoutAccountName: String?,

    @Column(name = "payout_inquiry_id", length = 128)
    var payoutInquiryId: String?,
) : TenantAwareJpaEntity(id)
