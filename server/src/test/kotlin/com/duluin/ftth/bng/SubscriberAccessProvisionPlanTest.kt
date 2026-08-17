package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.inbound.ProvisionAccessCommand
import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.BngActionService
import com.duluin.ftth.bng.application.service.SubscriberAccessService
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.SubscriptionRef
import com.duluin.ftth.customer.UpdateCustomerBiodataCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Menguji dari mana paket sebuah akun jaringan berasal saat diprovisi, dan bagaimana selisih
 * paket-akun vs paket-langganan ditandai.
 *
 * Kasus lapangan yang jadi asal-usulnya: pelanggan berlangganan 100 Mbps, tapi akunnya lahir di
 * paket 50 Mbps karena dropdown paket di form provisi tak pernah disentuh operator. Tak ada satu
 * pun layar yang menunjukkan selisih itu, jadi pelanggan berjalan di kecepatan yang salah selama
 * berbulan-bulan sambil ditagih penuh. Dua jaring dipasang di sini: paket akun MEWARISI paket
 * langganan bila tak ditentukan, dan bila memang sengaja berbeda, selisihnya DITANDAI di view.
 */
class SubscriberAccessProvisionPlanTest {

    private val tenantId: UUID = UuidV7.generate()
    private val customerId: UUID = UuidV7.generate()
    private val subscriptionId: UUID = UuidV7.generate()
    private val paketLangganan: UUID = UuidV7.generate()
    private val paketLain: UUID = UuidV7.generate()

    @Test
    fun `paket akun diwarisi dari paket langganan bila operator tak menentukannya`() {
        val f = fixture()

        val view = f.service.provision(perintah(planId = null))

        assertThat(view.planId).isEqualTo(paketLangganan)
        assertThat(view.planName).isEqualTo("Home 100 Mbps")
        // Selaras dengan tagihan → tak ada yang perlu ditandai.
        assertThat(view.subscriptionPlanName).isNull()
    }

    @Test
    fun `paket akun yang sengaja dibedakan tetap dipakai, tapi selisihnya ditandai`() {
        val f = fixture()

        val view = f.service.provision(perintah(planId = paketLain))

        // Keputusan operator dihormati — akses cadangan/kesepakatan khusus itu nyata…
        assertThat(view.planId).isEqualTo(paketLain)
        // …tapi tak lagi diam-diam: nama paket yang SEBENARNYA ditagih ikut terbawa ke layar.
        assertThat(view.subscriptionPlanName).isEqualTo("Home 100 Mbps")
    }

    @Test
    fun `daftar akun ikut menandai selisih paket-akun terhadap paket langganan`() {
        val f = fixture()
        f.service.provision(perintah(planId = paketLain))

        val view = f.service.listForCustomer(customerId).single()

        assertThat(view.planName).isEqualTo("Home 10 Mbps")
        assertThat(view.subscriptionPlanName).isEqualTo("Home 100 Mbps")
    }

    @Test
    fun `akun yang paketnya sama dengan langganan tak ditandai apa pun`() {
        val f = fixture()
        f.service.provision(perintah(planId = paketLangganan))

        val view = f.service.listForCustomer(customerId).single()

        assertThat(view.subscriptionPlanName).isNull()
    }

    @Test
    fun `langganan tanpa paket katalog wajib memilih paket akun secara eksplisit`() {
        val f = fixture(subscriptionPlanId = null)

        assertThatThrownBy { f.service.provision(perintah(planId = null)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("paket katalog")
    }

    @Test
    fun `langganan paket ad-hoc tak dianggap berselisih dengan paket akun`() {
        val f = fixture(subscriptionPlanId = null)
        f.service.provision(perintah(planId = paketLain))

        val view = f.service.listForCustomer(customerId).single()

        // Tak ada paket katalog di sisi langganan → tak ada yang bisa dibandingkan. Menandainya
        // di sini cuma akan melatih operator mengabaikan penanda yang sesungguhnya penting.
        assertThat(view.subscriptionPlanName).isNull()
    }

    // ---- Fixture & fake ----

    private class Fixture(
        val service: SubscriberAccessService,
        val accessRepo: ProvPlanFakeAccessRepo,
    )

    private fun fixture(subscriptionPlanId: UUID? = paketLangganan): Fixture {
        val accessRepo = ProvPlanFakeAccessRepo()
        val currentUser = ProvPlanFakeCurrentUser(tenantId)
        val customerApi = ProvPlanFakeCustomerApi(
            SubscriptionRef(subscriptionId, customerId, subscriptionPlanId, "Home 100 Mbps", 100, "ACTIVE"),
        )
        val service = SubscriberAccessService(
            subscriberAccessRepository = accessRepo,
            accountingRecordRepository = ProvPlanFakeAccountingRepo(),
            catalogApi = ProvPlanFakeCatalogApi(
                mapOf(
                    paketLangganan to plan(paketLangganan, "Home 100 Mbps", 100),
                    paketLain to plan(paketLain, "Home 10 Mbps", 10),
                ),
            ),
            nasRepository = ProvPlanFakeNasRepo(),
            customerApi = customerApi,
            currentUser = currentUser,
            auditor = AuditRecorder(ApplicationEventPublisher {}, currentUser),
            bngActions = BngActionService(ProvPlanFakeActionRepo(), accessRepo),
        )
        return Fixture(service, accessRepo)
    }

    /** BRAS sengaja dikosongkan: yang diuji asal-usul PAKET, bukan jalur tulis ke RADIUS. */
    private fun perintah(planId: UUID?) = ProvisionAccessCommand(
        subscriptionId = subscriptionId,
        username = "pelanggan01",
        secret = "rahasia123",
        planId = planId,
        nasId = null,
    )

    private fun plan(id: UUID, name: String, downMbps: Int) = PlanNetworkRef(
        planId = id,
        name = name,
        downMbps = downMbps,
        upMbps = downMbps / 5,
        rateLimit = "${downMbps / 5}M/${downMbps}M",
        connectionLimit = null,
        fupEnabled = false,
        fupQuotaMb = null,
        fupRateLimit = null,
        fupDownMbps = null,
        fupUpMbps = null,
        serviceTypes = setOf("PPPOE"),
    )
}

private class ProvPlanFakeAccessRepo : SubscriberAccessRepository {
    val tersimpan = mutableListOf<SubscriberAccess>()
    override fun save(access: SubscriberAccess): SubscriberAccess {
        tersimpan.removeIf { it.id == access.id }
        tersimpan += access
        return access
    }

    override fun findById(id: UUID): SubscriberAccess? = tersimpan.firstOrNull { it.id == id }
    override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> =
        tersimpan.filter { it.customerId == customerId }

    override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> =
        tersimpan.filter { it.subscriptionId == subscriptionId }

    override fun findByUsername(username: String): SubscriberAccess? =
        tersimpan.firstOrNull { it.username == username }

    override fun existsBySubscriptionId(subscriptionId: UUID): Boolean =
        tersimpan.any { it.subscriptionId == subscriptionId }

    override fun findAll(): List<SubscriberAccess> = tersimpan
    override fun findByCustomerIds(customerIds: Collection<UUID>): List<SubscriberAccess> = notUsed()
    override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
    override fun findActiveOnNas(): List<SubscriberAccess> = notUsed()
    override fun findIsolatedOnNas(): List<SubscriberAccess> = notUsed()
    override fun findActiveMacUsernames(): List<String> = notUsed()
    override fun countByNasId(nasId: UUID): Long = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class ProvPlanFakeActionRepo : BngActionRepository {
    override fun save(action: BngAction): BngAction = action
    override fun findById(id: UUID): BngAction? = null
    override fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction> = emptyList()
    override fun findServerProvisioningPending(limit: Int): List<BngAction> = emptyList()
    override fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int): List<BngAction> = emptyList()
    override fun findAccessIdsWithPendingProvisioning(subscriberAccessIds: Collection<UUID>): Set<UUID> = emptySet()
}

private class ProvPlanFakeNasRepo : NasRepository {
    override fun findAll(): List<Nas> = emptyList()
    override fun save(nas: Nas): Nas = nas
    override fun findById(id: UUID): Nas? = null
    override fun existsByName(name: String): Boolean = false
    override fun findByNameIgnoreCase(name: String): Nas? = null
    override fun deleteById(id: UUID) = Unit
}

private class ProvPlanFakeAccountingRepo : AccountingRecordRepository {
    override fun saveAll(points: List<AccountingRecordPoint>) = Unit
    override fun trafficSince(subscriberAccessId: UUID, since: Instant, bucketSeconds: Long) = emptyList<Nothing>()
    override fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long> = emptyMap()
}

private class ProvPlanFakeCatalogApi(private val plans: Map<UUID, PlanNetworkRef>) : CatalogApi {
    override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = plans[planId]
    override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = throw UnsupportedOperationException()
    override fun findPlanByName(name: String) = throw UnsupportedOperationException()
    override fun findActivePlans() = throw UnsupportedOperationException()
}

private class ProvPlanFakeCurrentUser(private val tenantId: UUID) : CurrentUserProvider {
    override fun currentOrNull() = AuthenticatedUser(
        userId = UuidV7.generate(),
        tenantId = tenantId,
        email = "operator@demo.ftth",
        name = "Operator",
        platformAdmin = false,
        permissions = setOf("bng.access.manage"),
        areaIds = emptySet(),
    )
}

private class ProvPlanFakeCustomerApi(private val subscription: SubscriptionRef) : CustomerApi {
    override fun findSubscription(id: UUID): SubscriptionRef? = subscription.takeIf { it.id == id }
    override fun findCustomer(id: UUID): CustomerRef? = null
    override fun findCustomersByIds(ids: Set<UUID>) = throw UnsupportedOperationException()
    override fun findSubscriptionByCustomer(customerId: UUID) = throw UnsupportedOperationException()
    override fun findOccupantsOfOdp(odpId: UUID) = throw UnsupportedOperationException()
    override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
    override fun findPlacementOf(customerId: UUID) = throw UnsupportedOperationException()
    override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
    override fun countOccupantsByOdp(odpIds: Set<UUID>) = throw UnsupportedOperationException()
    override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?) = throw UnsupportedOperationException()
    override fun findOnusBySerialNumbers(serialNumbers: Set<String>) = throw UnsupportedOperationException()
    override fun placementsForOnus(onuIds: Set<UUID>) = throw UnsupportedOperationException()
    override fun recordObservedOnuStatuses(statuses: Map<UUID, String>) = throw UnsupportedOperationException()
    override fun provisionOnu(command: ProvisionOnuCommand) = throw UnsupportedOperationException()
    override fun findBillableSubscriptions() = throw UnsupportedOperationException()
    override fun findBillableSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun isolateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun registerCustomer(command: RegisterCustomerCommand) = throw UnsupportedOperationException()
    override fun updateCustomerBiodata(command: UpdateCustomerBiodataCommand) = throw UnsupportedOperationException()
    override fun activateImportedSubscription(
        subscriptionId: UUID,
        activatedAt: Instant?,
        billingDayOfMonth: Int?,
    ) = throw UnsupportedOperationException()

    override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) =
        throw UnsupportedOperationException()

    override fun subscriberStats() = throw UnsupportedOperationException()
    override fun findExportRows(subscriptionIds: Set<UUID>) = throw UnsupportedOperationException()
    override fun subscriptionDimensions(subscriptionIds: Set<UUID>) = throw UnsupportedOperationException()
    override fun churnReport(from: LocalDate, to: LocalDate) = throw UnsupportedOperationException()
}
