package com.duluin.ftth.customer.application.service

import com.duluin.ftth.customer.application.port.inbound.CustomerView
import com.duluin.ftth.customer.application.port.inbound.OnuView
import com.duluin.ftth.customer.application.port.inbound.SubscriptionView
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.Onu
import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Merakit [CustomerView] dari tiga agregat plus label ODP milik module network.
 *
 * Dipisahkan dari service karena dipakai bersama oleh service pelanggan,
 * langganan, dan ONU — dan karena di sinilah pengambilan data dibuat berbasis
 * batch: satu halaman berisi 20 pelanggan tetap hanya menghasilkan sejumlah
 * tetap query, bukan 20 kali lipat.
 */
@Component
class CustomerAssembler(
    private val networkApi: NetworkApi,
) {
    fun toViews(
        customers: List<Customer>,
        subscriptions: List<Subscription>,
        onus: List<Onu>,
    ): List<CustomerView> {
        val odpCodes = odpCodesOf(onus)
        // Satu langganan per pelanggan (V107) — associateBy, bukan groupBy.
        val subByCustomer = subscriptions.associateBy { it.customerId }
        val onusByCustomer = onus.groupBy { it.customerId }
        return customers.map { customer ->
            customer.toView(
                subscription = subByCustomer[customer.id]?.toView(),
                onus = onusByCustomer[customer.id].orEmpty().map { it.toView(odpCodes[it.odpId]) },
            )
        }
    }

    fun toOnuViews(onus: List<Onu>): List<OnuView> {
        val odpCodes = odpCodesOf(onus)
        return onus.map { it.toView(odpCodes[it.odpId]) }
    }

    private fun odpCodesOf(onus: List<Onu>): Map<UUID, String> =
        networkApi.findOdpsByIds(onus.mapNotNullTo(HashSet()) { it.odpId }).associate { it.id to it.code }
}

internal fun Customer.toView(subscription: SubscriptionView?, onus: List<OnuView>) = CustomerView(
    id = id,
    code = code,
    name = name,
    phone = phone,
    email = email,
    address = address,
    location = location,
    areaId = areaId,
    idCardNumber = idCardNumber,
    status = status,
    subscription = subscription,
    onus = onus,
)

internal fun Subscription.toView() = SubscriptionView(
    id = id,
    customerId = customerId,
    planId = planId,
    packageName = packageName,
    bandwidthMbps = bandwidthMbps,
    monthlyFee = monthlyFee,
    status = status,
    activatedAt = activatedAt,
    terminatedAt = terminatedAt,
)

internal fun Onu.toView(odpCode: String?) = OnuView(
    id = id,
    customerId = customerId,
    serialNumber = serialNumber,
    model = model,
    odpId = odpId,
    odpCode = odpCode,
    odpPortNumber = odpPortNumber,
    installRxPowerDbm = installRxPowerDbm,
    opticalHealth = opticalHealth(),
    status = status,
    installedAt = installedAt,
)
