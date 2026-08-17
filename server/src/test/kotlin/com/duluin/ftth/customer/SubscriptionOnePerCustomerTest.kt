package com.duluin.ftth.customer

import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.application.port.inbound.SaveSubscriptionCommand
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.SubscriptionRepository
import com.duluin.ftth.customer.application.service.SubscriptionService
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.customer.domain.model.CustomerStatus
import com.duluin.ftth.customer.domain.model.PlanSnapshot
import com.duluin.ftth.customer.domain.model.Subscription
import com.duluin.ftth.customer.domain.model.SubscriptionStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Satu pelanggan hanya boleh punya SATU langganan hidup.
 *
 * Sisi fisik sistem ini menempel pada pelanggan, bukan pada langganan: ONU, koordinat peta,
 * perangkat CPE, dan badge "menunggu instalasi" semuanya dibaca per-pelanggan. Langganan kedua
 * karena itu tak pernah punya ONU sendiri dan sesinya dipilih lewat tebakan. Yang paling mahal:
 * operator yang meng-upgrade paket dengan cara membuka langganan BARU meninggalkan yang lama
 * tetap hidup — pelanggan tertagih dua kali tanpa gejala apa pun di layar.
 *
 * Riwayat tetap boleh menumpuk: langganan yang sudah berakhir tak menghalangi pelanggan yang
 * kembali berlangganan (invoice lamanya masih menunjuk ke sana).
 */
class SubscriptionOnePerCustomerTest {

    private val tenantId: UUID = UuidV7.generate()
    private val paketBaru: UUID = UuidV7.generate()

    @Test
    fun `pelanggan yang belum punya langganan boleh dibukakan langganan`() {
        val f = fixture()

        val view = f.service.create(f.customerId, SaveSubscriptionCommand(paketBaru, null))

        assertThat(view.packageName).isEqualTo("Home 100 Mbps")
        assertThat(view.status).isEqualTo(SubscriptionStatus.PENDING)
    }

    @Test
    fun `langganan kedua ditolak selama yang lama masih aktif`() {
        val f = fixture(langgananLama = SubscriptionStatus.ACTIVE)

        assertThatThrownBy { f.service.create(f.customerId, SaveSubscriptionCommand(paketBaru, null)) }
            .isInstanceOf(ConflictException::class.java)
            // Pesannya harus menyebut jalan keluarnya — di sinilah operator membacanya.
            .hasMessageContaining("PLG-001")
            .hasMessageContaining("Home 50 Mbps")
            .hasMessageContaining("aktif")
            .hasMessageContaining("ganti paket lewat sunting langganan")

        // Tak ada yang tersimpan diam-diam sebelum penolakan.
        assertThat(f.subscriptions.tersimpan).hasSize(1)
    }

    @Test
    fun `langganan yang sedang terisolir tetap menghalangi — kontraknya belum berakhir`() {
        val f = fixture(langgananLama = SubscriptionStatus.ISOLATED)

        assertThatThrownBy { f.service.create(f.customerId, SaveSubscriptionCommand(paketBaru, null)) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("terisolir")
    }

    @Test
    fun `langganan yang baru dijual dan belum dipasang pun menghalangi`() {
        val f = fixture(langgananLama = SubscriptionStatus.PENDING)

        assertThatThrownBy { f.service.create(f.customerId, SaveSubscriptionCommand(paketBaru, null)) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("menunggu instalasi")
    }

    @Test
    fun `langganan yang sudah berakhir tak menghalangi pelanggan berlangganan lagi`() {
        val f = fixture(langgananLama = SubscriptionStatus.TERMINATED)

        val view = f.service.create(f.customerId, SaveSubscriptionCommand(paketBaru, null))

        assertThat(view.packageName).isEqualTo("Home 100 Mbps")
        // Riwayatnya tetap ada di sampingnya, bukan tergantikan.
        assertThat(f.subscriptions.tersimpan).hasSize(2)
    }

    private fun fixture(langgananLama: SubscriptionStatus? = null): Fixture {
        val customer = Customer.create(
            tenantId = tenantId,
            code = "PLG-001",
            name = "Fajar",
            phone = null,
            email = null,
            address = "Jl. Mawar 1",
            location = Coordinate(107.6, -6.9),
            areaId = null,
            status = CustomerStatus.ACTIVE,
        )
        val subscriptions = SatuLanggananFakeSubRepo()
        if (langgananLama != null) {
            subscriptions.save(subscriptionLama(customer.id, langgananLama))
        }
        val service = SubscriptionService(
            subscriptionRepository = subscriptions,
            customerRepository = SatuLanggananFakeCustomerRepo(customer),
            catalog = SatuLanggananFakeCatalog(
                PlanCommercialRef(
                    planId = paketBaru,
                    packageName = "Home 100 Mbps",
                    monthlyFee = BigDecimal("250000"),
                    bandwidthMbps = 100,
                    active = true,
                    prorateOnActivation = null,
                    billingDayOfMonth = null,
                    dueDays = null,
                    graceDays = null,
                    autoIsolir = null,
                ),
            ),
            auditor = AuditRecorder(ApplicationEventPublisher {}, SatuLanggananFakeCurrentUser(tenantId)),
            events = ApplicationEventPublisher {},
        )
        return Fixture(customer.id, subscriptions, service)
    }

    /** Langganan yang sudah dipegang pelanggan, dibawa ke status yang diuji. */
    private fun subscriptionLama(customerId: UUID, status: SubscriptionStatus): Subscription {
        val lama = Subscription.create(
            tenantId, customerId,
            PlanSnapshot(
                planId = UuidV7.generate(),
                packageName = "Home 50 Mbps",
                bandwidthMbps = 50,
                monthlyFee = BigDecimal("175000"),
                prorateOnActivation = null,
                billingDayOfMonth = null,
                graceDays = null,
                autoIsolir = null,
            ),
        )
        when (status) {
            SubscriptionStatus.PENDING -> Unit
            SubscriptionStatus.ACTIVE -> lama.activate()
            SubscriptionStatus.ISOLATED -> { lama.activate(); lama.isolate() }
            SubscriptionStatus.TERMINATED -> { lama.activate(); lama.terminate() }
        }
        return lama
    }

    private class Fixture(
        val customerId: UUID,
        val subscriptions: SatuLanggananFakeSubRepo,
        val service: SubscriptionService,
    )
}

private class SatuLanggananFakeSubRepo : SubscriptionRepository {
    val tersimpan = mutableListOf<Subscription>()

    override fun save(subscription: Subscription): Subscription {
        tersimpan.removeIf { it.id == subscription.id }
        tersimpan += subscription
        return subscription
    }

    override fun findById(id: UUID): Subscription? = tersimpan.firstOrNull { it.id == id }
    override fun findByCustomerId(customerId: UUID): List<Subscription> =
        tersimpan.filter { it.customerId == customerId }

    override fun findByCustomerIds(customerIds: Set<UUID>): List<Subscription> = notUsed()
    override fun findByIds(ids: Set<UUID>): List<Subscription> = notUsed()
    override fun findBillableForCurrentTenant(): List<Subscription> = notUsed()
    override fun countByStatus(): Map<SubscriptionStatus, Long> = notUsed()
    override fun sumMonthlyRecurringRevenue(): BigDecimal = notUsed()
    override fun countActivatedBetween(from: Instant, toExclusive: Instant): Long = notUsed()
    override fun countTerminatedBetween(from: Instant, toExclusive: Instant): Long = notUsed()
    override fun countLiveAt(at: Instant): Long = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class SatuLanggananFakeCustomerRepo(private val customer: Customer) : CustomerRepository {
    override fun findById(id: UUID): Customer? = customer.takeIf { it.id == id }
    override fun save(customer: Customer): Customer = notUsed()
    override fun findAllByIds(ids: Set<UUID>): List<Customer> = notUsed()
    override fun findAwaitingInstallation(areaIds: Set<UUID>?): List<Customer> = notUsed()
    override fun findUnmapped(query: String, areaIds: Set<UUID>?, limit: Int): List<Customer> = notUsed()
    override fun search(
        query: String,
        areaIds: Set<UUID>?,
        status: CustomerStatus?,
        pageRequest: PageRequest,
    ): Page<Customer> = notUsed()

    override fun existsByCode(code: String): Boolean = notUsed()
    override fun count(): Long = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class SatuLanggananFakeCatalog(private val plan: PlanCommercialRef) : CatalogApi {
    override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = plan.takeIf { it.planId == planId }
    override fun findPlanByName(name: String) = throw UnsupportedOperationException()
    override fun findPlanNetwork(planId: UUID) = throw UnsupportedOperationException()
    override fun findActivePlans() = throw UnsupportedOperationException()
}

private class SatuLanggananFakeCurrentUser(private val tenantId: UUID) : CurrentUserProvider {
    override fun currentOrNull() = AuthenticatedUser(
        userId = UuidV7.generate(),
        tenantId = tenantId,
        email = "operator@demo.ftth",
        name = "Operator",
        platformAdmin = false,
        permissions = setOf("customer.subscription.update"),
        areaIds = emptySet(),
    )
}
