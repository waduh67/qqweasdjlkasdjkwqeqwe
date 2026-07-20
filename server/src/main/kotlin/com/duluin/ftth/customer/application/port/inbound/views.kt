package com.duluin.ftth.customer.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.customer.domain.model.CustomerStatus
import com.duluin.ftth.customer.domain.model.OnuStatus
import com.duluin.ftth.customer.domain.model.OpticalHealth
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class CustomerView(
    val id: UUID,
    val code: String,
    val name: String,
    val phone: String?,
    val email: String?,
    val address: String,
    val location: Coordinate,
    val areaId: UUID?,
    val status: CustomerStatus,
    val subscriptions: List<SubscriptionView>,
    val onus: List<OnuView>,
) {
    /** Ditandai di UI: pelanggan aktif yang ONU-nya belum terpasang ke ODP mana pun. */
    val awaitingInstallation: Boolean
        get() = status != CustomerStatus.TERMINATED && onus.none { it.odpId != null }
}

data class SubscriptionView(
    val id: UUID,
    val customerId: UUID,
    val packageName: String,
    val bandwidthMbps: Int,
    val monthlyFee: BigDecimal,
    val status: SubscriptionStatus,
    val activatedAt: Instant?,
    val terminatedAt: Instant?,
)

data class OnuView(
    val id: UUID,
    val customerId: UUID,
    val serialNumber: String,
    val model: String?,
    val odpId: UUID?,
    val odpCode: String?,
    val odpPortNumber: Int?,
    val installRxPowerDbm: Double?,
    val opticalHealth: OpticalHealth,
    val status: OnuStatus,
    val installedAt: Instant?,
)
