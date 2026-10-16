package com.karuhun.ftth.subscriber360.application.service

import com.karuhun.ftth.billing.BillingApi
import com.karuhun.ftth.bng.BngApi
import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.common.infrastructure.security.AccessChecker
import com.karuhun.ftth.cpe.CpeApi
import com.karuhun.ftth.customer.CustomerApi
import com.karuhun.ftth.customer.CustomerReadAccessApi
import com.karuhun.ftth.subscriber360.application.port.inbound.Subscriber360Access
import com.karuhun.ftth.subscriber360.application.port.inbound.Subscriber360Query
import com.karuhun.ftth.subscriber360.application.port.inbound.Subscriber360View
import com.karuhun.ftth.workorder.WorkorderApi
import com.karuhun.ftth.inventory.MaterialConsumptionApi
import com.karuhun.ftth.inventory.MaterialConsumptionApiV2
import com.karuhun.ftth.inventory.WarehousePageRequest
import com.karuhun.ftth.common.security.CurrentUserProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Merakit pandangan 360° pelanggan dari kontrak publik modul lain — murni baca, tak
 * punya persistence sendiri (pola sama `gis`/MapService). Endpoint di-anchor pada
 * `customer.customer.view`; tiap facet lintas-modul digating izin modulnya sendiri di
 * sini agar operator hanya melihat data yang boleh ia lihat, dan UI tahu facet mana yang
 * terkunci (lewat [Subscriber360Access]).
 */
@Service
@Transactional(timeout = 20)
class Subscriber360Service(
    private val customerApi: CustomerApi,
    private val bngApi: BngApi,
    private val billingApi: BillingApi,
    private val cpeApi: CpeApi,
    private val workorderApi: WorkorderApi,
    private val authz: AccessChecker,
    private val materialApi: MaterialConsumptionApi,
    private val currentUser: CurrentUserProvider,
    private val materialApiV2: MaterialConsumptionApiV2,
    private val customerReadAccess: CustomerReadAccessApi,
) : Subscriber360Query {

    @Suppress("DEPRECATION")
    override fun assemble(customerId: UUID, materialPage: WarehousePageRequest): Subscriber360View {
        customerReadAccess.requireVisibleCustomer(customerId)
        val customer = customerApi.findCustomer(customerId)
            ?: throw NotFoundException("Pelanggan $customerId tidak ditemukan")

        val canSubscription = authz.can("customer.subscription.view")
        val canPlacement = authz.can("customer.onu.view")
        val canSession = authz.can("bng.session.view")
        val canBilling = authz.can("billing.invoice.view")
        val canCpe = authz.can("cpe.device.view")
        val canWorkOrder = authz.can("workorder.order.view")

        return Subscriber360View(
            customer = customer,
            subscription = if (canSubscription) customerApi.findSubscriptionByCustomer(customerId) else null,
            placement = if (canPlacement) customerApi.findPlacementOf(customerId) else null,
            session = if (canSession) bngApi.findSubscriberSession(customerId) else null,
            billing = if (canBilling) billingApi.findAccountSummary(customerId) else null,
            cpeDevices = if (canCpe) cpeApi.findDevicesForCustomer(customerId) else null,
            // Peta open-PSB dihitung untuk seluruh tenant lalu diambil satu pelanggan —
            // set WO pasang terbuka biasanya kecil, jadi masih murah untuk pandangan satu ini.
            openWorkOrder = if (canWorkOrder) workorderApi.openPsbByCustomer()[customerId] else null,
            materialHistory = if (canWorkOrder) materialApi.forCustomer(currentUser.current().tenantId, customerId) else null,
            materialHistoryV2 = if (canWorkOrder) materialApiV2.forCustomer(customerId, materialPage) else null,
            access = Subscriber360Access(
                subscription = canSubscription,
                placement = canPlacement,
                session = canSession,
                billing = canBilling,
                cpe = canCpe,
                workOrder = canWorkOrder,
                materialHistory = canWorkOrder,
                materialHistoryV2 = canWorkOrder,
            ),
        )
    }
}
