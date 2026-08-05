package com.duluin.ftth.subscriber360

import com.duluin.ftth.billing.BillingAccountSummary
import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.ProvisionAccessSpec
import com.duluin.ftth.bng.ProvisionedAccessRef
import com.duluin.ftth.bng.SubscriberSessionRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.security.AccessChecker
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.cpe.CpeDeviceStatusRef
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerPlacement
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.OdpOccupant
import com.duluin.ftth.customer.OnuPlacementRef
import com.duluin.ftth.customer.OnuRef
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.SubscriptionRef
import com.duluin.ftth.subscriber360.application.service.Subscriber360Service
import com.duluin.ftth.workorder.RaisePsbCommand
import com.duluin.ftth.workorder.WorkOrderRef
import com.duluin.ftth.workorder.WorkorderApi
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Menguji perakitan 360° dan — yang paling penting — gating izin per-facet: pengguna
 * berizin penuh melihat semua facet; pengguna yang hanya boleh melihat pelanggan
 * mendapat facet lintas-modul null/kosong dengan [access] menandai semuanya terkunci.
 */
class Subscriber360ServiceTest {

    private val customerId = UuidV7.generate()

    @Test
    fun `pengguna berizin penuh melihat semua facet`() {
        val service = service(
            permissions = ALL_FACET_PERMISSIONS,
            customer = FakeCustomerApi(present = true),
        )

        val view = service.assemble(customerId)

        assertThat(view.customer.id).isEqualTo(customerId)
        assertThat(view.subscriptions).hasSize(1)
        assertThat(view.placement).isNotNull()
        assertThat(view.session).isNotNull()
        assertThat(view.billing).isNotNull()
        assertThat(view.cpeDevices).isNotNull().hasSize(1)
        assertThat(view.openWorkOrder).isNotNull()
        assertThat(view.access).isEqualTo(
            com.duluin.ftth.subscriber360.application.port.inbound.Subscriber360Access(
                subscriptions = true, placement = true, session = true,
                billing = true, cpe = true, workOrder = true,
            ),
        )
    }

    @Test
    fun `pengguna hanya boleh lihat pelanggan mendapat facet lintas-modul terkunci`() {
        val service = service(
            permissions = setOf("customer.customer.view"),
            customer = FakeCustomerApi(present = true),
        )

        val view = service.assemble(customerId)

        // Facet inti tetap ada.
        assertThat(view.customer.id).isEqualTo(customerId)
        // Facet digating → null/kosong.
        assertThat(view.subscriptions).isEmpty()
        assertThat(view.placement).isNull()
        assertThat(view.session).isNull()
        assertThat(view.billing).isNull()
        assertThat(view.cpeDevices).isNull() // null (ditolak), bukan list kosong
        assertThat(view.openWorkOrder).isNull()
        assertThat(view.access.subscriptions).isFalse()
        assertThat(view.access.billing).isFalse()
        assertThat(view.access.cpe).isFalse()
    }

    @Test
    fun `pelanggan tak ditemukan melempar NotFound`() {
        val service = service(
            permissions = ALL_FACET_PERMISSIONS,
            customer = FakeCustomerApi(present = false),
        )

        assertThatThrownBy { service.assemble(customerId) }
            .isInstanceOf(NotFoundException::class.java)
    }

    // --- Perkakas uji ---

    private fun service(permissions: Set<String>, customer: CustomerApi) = Subscriber360Service(
        customerApi = customer,
        bngApi = FakeBngApi(),
        billingApi = FakeBillingApi(),
        cpeApi = FakeCpeApi(),
        workorderApi = FakeWorkorderApi(customerId),
        authz = AccessChecker(FakeCurrentUser(permissions)),
    )

    private inner class FakeCurrentUser(private val permissions: Set<String>) : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser = AuthenticatedUser(
            userId = UuidV7.generate(),
            tenantId = UuidV7.generate(),
            email = "op@tenant.test",
            name = "Operator",
            platformAdmin = false,
            permissions = permissions,
            areaIds = emptySet(),
        )
    }

    private inner class FakeCustomerApi(private val present: Boolean) : CustomerApi {
        override fun findCustomer(id: UUID): CustomerRef? =
            if (present) CustomerRef(id, "C-001", "Budi", "0812", Coordinate(106.8, -6.2), "ACTIVE") else null

        override fun findSubscriptionsByCustomer(customerId: UUID): List<SubscriptionRef> =
            listOf(SubscriptionRef(UuidV7.generate(), customerId, UuidV7.generate(), "Home 100", 100, "ACTIVE"))

        override fun findPlacementOf(customerId: UUID): CustomerPlacement =
            CustomerPlacement(UuidV7.generate(), UuidV7.generate(), 3, "SN-1", "ONLINE", "GOOD", -22.0)

        override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
        override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
        override fun findOccupantsOfOdp(odpId: UUID): List<OdpOccupant> = throw UnsupportedOperationException()
        override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
        override fun countOccupantsByOdp(odpIds: Set<UUID>) = throw UnsupportedOperationException()
        override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?) = throw UnsupportedOperationException()
        override fun findOnusBySerialNumbers(serialNumbers: Set<String>): List<OnuRef> = throw UnsupportedOperationException()
        override fun placementsForOnus(onuIds: Set<UUID>): List<OnuPlacementRef> = throw UnsupportedOperationException()
        override fun recordObservedOnuStatuses(statuses: Map<UUID, String>) = throw UnsupportedOperationException()
        override fun provisionOnu(command: ProvisionOnuCommand) = throw UnsupportedOperationException()
        override fun findBillableSubscriptions() = throw UnsupportedOperationException()
        override fun findBillableSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun isolateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
        override fun registerCustomer(command: RegisterCustomerCommand) = throw UnsupportedOperationException()
        override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: BigDecimal?) =
            throw UnsupportedOperationException()

        override fun subscriberStats() = throw UnsupportedOperationException()
    }

    private inner class FakeBngApi : BngApi {
        override fun findSubscriberSession(customerId: UUID): SubscriberSessionRef = SubscriberSessionRef(
            subscriberAccessId = UuidV7.generate(), username = "budi", accessStatus = "ACTIVE",
            rateProfileName = "Home 100", online = true, framedIp = "100.64.0.5",
            nasId = null, nasName = null, nasIp = null, uptimeSeconds = 120, startedAt = null, lastSeenAt = null,
        )

        override fun provisionAccess(command: ProvisionAccessSpec): ProvisionedAccessRef =
            throw UnsupportedOperationException()

        override fun resolveNasForArea(areaId: UUID): UUID? = null

        override fun fetchPppSecretsFromNas(nasId: UUID) = emptyList<com.duluin.ftth.bng.PppSecretRef>()

        override fun activeSubscriberLiveness() = emptyList<com.duluin.ftth.bng.SubscriberPppoeLiveness>()
    }

    private inner class FakeBillingApi : BillingApi {
        override fun findAccountSummary(customerId: UUID) = BillingAccountSummary(
            customerId = customerId, outstandingAmount = BigDecimal("100000"),
            outstandingCount = 1, unpaidCount = 1, oldestDueDate = null, lastPaidAt = null,
        )

        override fun financialReport(from: java.time.LocalDate, to: java.time.LocalDate) =
            throw UnsupportedOperationException()

        override fun monthlyRevenue(fromMonth: java.time.YearMonth, toMonth: java.time.YearMonth) =
            throw UnsupportedOperationException()
    }

    private inner class FakeCpeApi : CpeApi {
        override fun findDevicesForCustomer(customerId: UUID): List<CpeDeviceStatusRef> = listOf(
            CpeDeviceStatusRef(UuidV7.generate(), "SN-CPE", "Huawei", "HG8145", "V1", "10.0.0.1", null, true),
        )
    }

    private inner class FakeWorkorderApi(private val forCustomer: UUID) : WorkorderApi {
        override fun openPsbByCustomer(): Map<UUID, WorkOrderRef> = mapOf(
            forCustomer to WorkOrderRef(UuidV7.generate(), "WO-1", forCustomer, null, null),
        )

        override fun raisePsb(command: RaisePsbCommand): WorkOrderRef = throw UnsupportedOperationException()
    }

    private companion object {
        val ALL_FACET_PERMISSIONS = setOf(
            "customer.customer.view", "customer.subscription.view", "customer.onu.view",
            "bng.session.view", "billing.invoice.view", "cpe.device.view", "workorder.order.view",
        )
    }
}
