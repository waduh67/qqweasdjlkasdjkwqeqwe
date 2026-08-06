package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

/**
 * Setelan billing global platform (singleton). Tabel platform-level (tanpa RLS).
 * Detail persistence — bukan model domain.
 */
@Entity
@Table(name = "platform_setting")
class PlatformSettingJpaEntity(
    id: UUID,

    @Column(name = "default_grace_days", nullable = false)
    var defaultGraceDays: Int,

    @Column(name = "default_due_days", nullable = false)
    var defaultDueDays: Int,

    @Column(name = "default_billing_day", nullable = false)
    var defaultBillingDay: Int,

    @Column(name = "default_monthly_fee", nullable = false)
    var defaultMonthlyFee: BigDecimal,

    @Column(nullable = false, length = 3)
    var currency: String,
) : BaseJpaEntity(id)
