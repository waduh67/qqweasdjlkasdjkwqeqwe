package com.duluin.ftth.customer

import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.UuidV7
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
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Satu pelanggan memegang TEPAT SATU langganan, seumur hidupnya.
 *
 * Sisi fisik sistem ini menempel pada pelanggan, bukan pada langganan: ONU, koordinat peta,
 * perangkat CPE, dan badge "menunggu instalasi" semuanya dibaca per-pelanggan. Langganan kedua
 * karena itu tak pernah punya ONU sendiri dan sesinya dipilih lewat tebakan. Yang paling mahal:
 * operator yang meng-upgrade paket dengan cara membuka langganan BARU meninggalkan yang lama
 * tetap hidup — pelanggan tertagih dua kali tanpa gejala apa pun di layar.
 *
 * Karena itu tak ada lagi operasi "tambah langganan": [SubscriptionService.setPlan] adalah
 * satu-satunya pintu, dan pelanggan yang kembali setelah berhenti menghidupkan ulang BARIS YANG
 * SAMA — bukan baris kedua. Uji di bawah menjaga ketiga cabangnya beserta akibat sampingannya.
 */
class SubscriptionOnePerCustomerTest {

    private val tenantId: UUID = UuidV7.generate()
    private val paketBaru: UUID = UuidV7.generate()

    @Test
    fun `pelanggan yang belum berpaket dibukakan langganan pertamanya`() {
        val f = fixture()

        val view = f.service.setPlan(f.customerId, SaveSubscriptionCommand(paketBaru, null))

        assertThat(view.packageName).isEqualTo("Home 100 Mbps")
        assertThat(view.status).isEqualTo(SubscriptionStatus.PENDING)
        assertThat(f.subscriptions.tersimpan).hasSize(1)
    }

    @Test
    fun `ganti paket menimpa langganan yang sama, bukan menumpuk yang kedua`() {
        val f = fixture(langgananLama = SubscriptionStatus.ACTIVE)
        val idLama = f.subscriptions.tersimpan.single().id

        val view = f.service.setPlan(f.customerId, SaveSubscriptionCommand(paketBaru, null))

        assertThat(f.subscriptions.tersimpan).hasSize(1)
        assertThat(view.id).isEqualTo(idLama)
        assertThat(view.packageName).isEqualTo("Home 100 Mbps")
        // Layanan tak boleh putus hanya karena paketnya naik — statusnya tetap seperti semula.
        assertThat(view.status).isEqualTo(SubscriptionStatus.ACTIVE)
        // Sisi jaringan harus tahu paketnya berpindah, supaya profil RADIUS ikut diselaraskan.
        assertThat(f.events.terbit.filterIsInstance<SubscriptionPlanChanged>()).singleElement()
            .extracting("planId").isEqualTo(paketBaru)
    }

    @Test
    fun `pelanggan menunggak yang naik paket tetap terisolir sampai tagihannya lunas`() {
        val f = fixture(langgananLama = SubscriptionStatus.ISOLATED)

        val view = f.service.setPlan(f.customerId, SaveSubscriptionCommand(paketBaru, null))

        assertThat(view.status).isEqualTo(SubscriptionStatus.ISOLATED)
    }

    @Test
    fun `menyimpan paket yang sama tak mengantre pekerjaan RADIUS`() {
        val f = fixture(langgananLama = SubscriptionStatus.ACTIVE, paketLama = paketBaru)

        f.service.setPlan(f.customerId, SaveSubscriptionCommand(paketBaru, null))

        assertThat(f.events.terbit).isEmpty()
    }

    @Test
    fun `pelanggan yang kembali berlangganan menghidupkan baris yang sama`() {
        val f = fixture(langgananLama = SubscriptionStatus.TERMINATED)
        val idLama = f.subscriptions.tersimpan.single().id

        val view = f.service.setPlan(f.customerId, SaveSubscriptionCommand(paketBaru, null))

        // Baris yang sama dihidupkan: akun PPPoE & riwayat tagihannya ikut terpakai lagi.
        assertThat(f.subscriptions.tersimpan).hasSize(1)
        assertThat(view.id).isEqualTo(idLama)
        assertThat(view.status).isEqualTo(SubscriptionStatus.PENDING)
        assertThat(view.terminatedAt).isNull()
        // Prorata dihitung dari pemasangan yang baru, bukan dari aktivasi bertahun lalu.
        assertThat(view.activatedAt).isNull()
    }

    private fun fixture(
        langgananLama: SubscriptionStatus? = null,
        paketLama: UUID = UuidV7.generate(),
    ): Fixture {
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
            subscriptions.save(subscriptionLama(customer.id, langgananLama, paketLama))
        }
        val events = SatuLanggananFakeEvents()
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
            events = events,
        )
        return Fixture(customer.id, subscriptions, events, service)
    }

    /** Langganan yang sudah dipegang pelanggan, dibawa ke status yang diuji. */
    private fun subscriptionLama(customerId: UUID, status: SubscriptionStatus, planId: UUID): Subscription {
        val lama = Subscription.create(
            tenantId, customerId,
            PlanSnapshot(
                planId = planId,
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
        val events: SatuLanggananFakeEvents,
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
    override fun findByCustomerId(customerId: UUID): Subscription? =
        tersimpan.firstOrNull { it.customerId == customerId }

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

/** Menangkap kejadian domain — yang diuji di sini: kapan RADIUS BOLEH disuruh bekerja. */
private class SatuLanggananFakeEvents : ApplicationEventPublisher {
    val terbit = mutableListOf<Any>()
    override fun publishEvent(event: Any) {
        terbit += event
    }
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
