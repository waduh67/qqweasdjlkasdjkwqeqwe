package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.customer.domain.model.CustomerStatus
import com.duluin.ftth.customer.domain.model.OnuStatus
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.locationtech.jts.geom.Point
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "customer")
class CustomerJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 40, updatable = false)
    var code: String,

    @Column(nullable = false, length = 150)
    var name: String,

    @Column(length = 30)
    var phone: String?,

    @Column(length = 255)
    var email: String?,

    @Column(nullable = false, length = 500)
    var address: String,

    @Column(columnDefinition = "geometry(Point,4326)")
    var location: Point?,

    @Enumerated(EnumType.STRING)
    @Column(name = "location_status", nullable = false, length = 20)
    var locationStatus: com.duluin.ftth.customer.domain.model.LocationStatus,

    @Column(name = "area_id")
    var areaId: UUID?,

    @Column(name = "id_card_number", length = 32)
    var idCardNumber: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CustomerStatus,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "subscription")
class SubscriptionJpaEntity(
    id: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(name = "plan_id")
    var planId: UUID?,

    @Column(name = "package_name", nullable = false, length = 100)
    var packageName: String,

    @Column(name = "bandwidth_mbps", nullable = false)
    var bandwidthMbps: Int,

    @Column(name = "monthly_fee", nullable = false, precision = 14, scale = 2)
    var monthlyFee: BigDecimal,

    @Column(name = "prorate_on_activation")
    var prorateOnActivation: Boolean?,

    @Column(name = "billing_day_of_month")
    var billingDayOfMonth: Int?,

    @Column(name = "grace_days")
    var graceDays: Int?,

    @Column(name = "auto_isolir")
    var autoIsolir: Boolean?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: SubscriptionStatus,

    @Column(name = "activated_at")
    var activatedAt: Instant?,

    @Column(name = "terminated_at")
    var terminatedAt: Instant?,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "onu")
class OnuJpaEntity(
    id: UUID,

    @Column(name = "customer_id", nullable = false, updatable = false)
    var customerId: UUID,

    @Column(name = "serial_number", nullable = false, length = 60, updatable = false)
    var serialNumber: String,

    @Column(name = "odp_id")
    var odpId: UUID?,

    @Column(name = "odp_port_number")
    var odpPortNumber: Int?,

    @Column(length = 80)
    var model: String?,

    @Column(name = "install_rx_power_dbm")
    var installRxPowerDbm: Double?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OnuStatus,

    @Column(name = "installed_at")
    var installedAt: Instant?,
) : TenantAwareJpaEntity(id)
