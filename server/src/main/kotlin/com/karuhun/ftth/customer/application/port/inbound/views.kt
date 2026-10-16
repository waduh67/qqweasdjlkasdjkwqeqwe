package com.karuhun.ftth.customer.application.port.inbound

import com.karuhun.ftth.common.domain.geo.Coordinate
import com.karuhun.ftth.customer.domain.model.CustomerStatus
import com.karuhun.ftth.customer.domain.model.LocationStatus
import com.karuhun.ftth.customer.domain.model.OnuStatus
import com.karuhun.ftth.customer.domain.model.OpticalHealth
import com.karuhun.ftth.customer.domain.model.SubscriptionStatus
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
    val location: Coordinate?,
    val locationStatus: LocationStatus,
    val areaId: UUID?,
    val idCardNumber: String?,
    val status: CustomerStatus,
    /**
     * Langganannya — satu, bukan daftar (V107). Tetap nullable demi pelanggan warisan yang
     * terlanjur terdaftar tanpa paket; pendaftaran baru selalu membawa paketnya.
     */
    val subscription: SubscriptionView?,
    val onus: List<OnuView>,
) {
    /** Pendaftaran berpaket yang layanannya belum diaktifkan. */
    val awaitingInstallation: Boolean
        get() = status == CustomerStatus.PROSPECT && subscription?.status == SubscriptionStatus.PENDING
}

/**
 * Baris ringkas untuk pemilih "pelanggan belum berkoordinat" di peta. Sengaja bukan
 * [CustomerView]: yang dibutuhkan hanya secukupnya untuk mengenali orangnya, sedangkan
 * langganan & ONU-nya menyeret dua kueri tambahan yang tak dipakai sama sekali di sana.
 */
data class UnmappedCustomerView(
    val id: UUID,
    val code: String,
    val name: String,
    val address: String,
    val phone: String?,
    val status: CustomerStatus,
)

data class SubscriptionView(
    val id: UUID,
    val customerId: UUID,
    /** Paket katalog sumber snapshot; null untuk langganan warisan. */
    val planId: UUID?,
    val packageName: String,
    val bandwidthMbps: Int,
    val monthlyFee: BigDecimal,
    val status: SubscriptionStatus,
    val activatedAt: Instant?,
    val terminatedAt: Instant?,
    val billingManaged: Boolean = true,
    val billingStartsOn: java.time.LocalDate? = null,
    val billingDayOfMonth: Int? = null,
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
    val assetId: UUID? = null,
    val assignmentId: UUID? = null,
    val provenance: com.karuhun.ftth.inventory.AssetProvenance = com.karuhun.ftth.inventory.AssetProvenance.UNKNOWN,
    val retiredAt: Instant? = null,
)
