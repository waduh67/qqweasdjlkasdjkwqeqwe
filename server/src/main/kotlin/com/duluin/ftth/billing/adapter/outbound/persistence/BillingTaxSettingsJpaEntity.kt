package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

/**
 * Satu baris setelan pajak per tenant. Mutable (setelan memang disunting berulang), tanpa
 * secret sehingga tak butuh enkripsi — beda dari `notification_settings`. Tarif adalah
 * pecahan di [0,1) (mis. 0.1100 untuk 11%).
 */
@Entity
@Table(name = "billing_tax_settings")
class BillingTaxSettingsJpaEntity(
    id: UUID,

    @Column(name = "ppn_enabled", nullable = false)
    var ppnEnabled: Boolean,

    @Column(name = "ppn_rate", nullable = false, precision = 6, scale = 4)
    var ppnRate: BigDecimal,

    @Column(name = "regulatory_enabled", nullable = false)
    var regulatoryEnabled: Boolean,

    @Column(name = "bhp_rate", nullable = false, precision = 6, scale = 4)
    var bhpRate: BigDecimal,

    @Column(name = "uso_rate", nullable = false, precision = 6, scale = 4)
    var usoRate: BigDecimal,
) : TenantAwareJpaEntity(id)
