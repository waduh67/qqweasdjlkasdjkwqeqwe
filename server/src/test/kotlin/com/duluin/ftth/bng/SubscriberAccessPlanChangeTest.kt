package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.BngActionService
import com.duluin.ftth.bng.application.service.SubscriberAccessLifecycle
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.RadiusGroups
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Menguji perpindahan paket langganan → paket akun jaringan
 * ([SubscriberAccessLifecycle.onPlanChanged]) dengan fake murni (tanpa Spring/DB).
 *
 * Yang dijaga di sini adalah selisih yang dulu tak pernah kelihatan: pelanggan ditagih paket
 * baru sementara BRAS masih memberi kecepatan paket lama. Sekaligus menjaga agar perbaikan itu
 * tak kebablasan — akun yang sedang terisolir TIDAK boleh ikut dikembalikan ke grup paketnya
 * hanya karena paket langganannya disunting.
 */
class SubscriberAccessPlanChangeTest {

    private val subscriptionId: UUID = UuidV7.generate()
    private val paketLama: UUID = UuidV7.generate()
    private val paketBaru: UUID = UuidV7.generate()

    @Test
    fun `akun ACTIVE ikut pindah paket lalu di-CoA agar sesi hidup langsung kena kecepatan baru`() {
        val akun = akun(AccessStatus.ACTIVE)
        val f = fixture(listOf(akun))

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        assertThat(akun.planId).isEqualTo(paketBaru)
        val provision = f.actionRepo.saved.single { it.action == BngActionType.PROVISION }
        assertThat(provision.groupname).isEqualTo(RadiusGroups.normal(paketBaru))
        val coa = f.actionRepo.saved.single { it.action == BngActionType.COA }
        assertThat(coa.downMbps).isEqualTo(100)
        assertThat(coa.upMbps).isEqualTo(20)
    }

    @Test
    fun `akun ISOLATED hanya dicatat paket barunya, keanggotaan grup isolirnya tak diusik`() {
        val akun = akun(AccessStatus.ISOLATED)
        val f = fixture(listOf(akun))

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        // Paket tercatat supaya jalur Pulihkan nanti memakai yang benar…
        assertThat(akun.planId).isEqualTo(paketBaru)
        // …tapi tak ada satu pun tulisan keanggotaan akun. Kalau ada, penunggak lolos dari
        // walled garden cuma karena paketnya disunting.
        assertThat(f.actionRepo.saved.map { it.action }).doesNotContain(BngActionType.PROVISION, BngActionType.COA)
    }

    @Test
    fun `akun PENDING hanya dicatat paket barunya, belum ditulis ke RADIUS`() {
        val akun = akun(AccessStatus.PENDING)
        val f = fixture(listOf(akun))

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        assertThat(akun.planId).isEqualTo(paketBaru)
        assertThat(f.actionRepo.saved.map { it.action }).doesNotContain(BngActionType.PROVISION, BngActionType.COA)
    }

    @Test
    fun `akun yang paketnya sudah sama tak diapa-apakan`() {
        val akun = akun(AccessStatus.ACTIVE, planId = paketBaru)
        val f = fixture(listOf(akun))

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        assertThat(f.accessRepo.saved).isEmpty()
        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `akun TERMINATED dilewati, bukan dilempar galat`() {
        val akun = akun(AccessStatus.ACTIVE).also { it.terminate() }
        val f = fixture(listOf(akun))

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        assertThat(akun.planId).isEqualTo(paketLama)
        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `paket baru tak ada di katalog tak mengubah apa pun`() {
        val akun = akun(AccessStatus.ACTIVE)
        val f = fixture(listOf(akun), plan = null)

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        assertThat(akun.planId).isEqualTo(paketLama)
        assertThat(f.accessRepo.saved).isEmpty()
        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `akun yang belum ditugaskan ke BRAS cukup dicatat paketnya`() {
        val akun = akun(AccessStatus.ACTIVE, nasId = null)
        val f = fixture(listOf(akun))

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        assertThat(akun.planId).isEqualTo(paketBaru)
        assertThat(f.accessRepo.saved).containsExactly(akun)
        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `semua akun langganan yang sama ikut pindah`() {
        val satu = akun(AccessStatus.ACTIVE)
        val dua = akun(AccessStatus.ACTIVE)
        val f = fixture(listOf(satu, dua))

        f.lifecycle.onPlanChanged(subscriptionId, paketBaru)

        assertThat(listOf(satu.planId, dua.planId)).containsOnly(paketBaru)
        assertThat(f.actionRepo.saved.filter { it.action == BngActionType.COA }).hasSize(2)
    }

    // ---- Fixture & fake ----

    private class Fixture(
        val lifecycle: SubscriberAccessLifecycle,
        val accessRepo: PlanChgFakeAccessRepo,
        val actionRepo: PlanChgFakeActionRepo,
    )

    private fun fixture(akun: List<SubscriberAccess>, plan: PlanNetworkRef? = planBaru()): Fixture {
        val accessRepo = PlanChgFakeAccessRepo(subscriptionId, akun)
        val actionRepo = PlanChgFakeActionRepo()
        val lifecycle = SubscriberAccessLifecycle(
            accessRepo,
            PlanChgFakeCatalogApi(mapOf(paketBaru to plan)),
            BngActionService(actionRepo, accessRepo),
        )
        return Fixture(lifecycle, accessRepo, actionRepo)
    }

    private fun akun(
        status: AccessStatus,
        planId: UUID = paketLama,
        nasId: UUID? = UuidV7.generate(),
    ) = SubscriberAccess.create(
        tenantId = UuidV7.generate(),
        subscriptionId = subscriptionId,
        customerId = UuidV7.generate(),
        username = "u${UUID.randomUUID().toString().take(6)}",
        secret = "rahasia123",
        planId = planId,
        nasId = nasId,
        status = status,
    )

    private fun planBaru() = PlanNetworkRef(
        planId = paketBaru,
        name = "Home 100 Mbps",
        downMbps = 100,
        upMbps = 20,
        rateLimit = "20M/100M",
        connectionLimit = null,
        fupEnabled = false,
        fupQuotaMb = null,
        fupRateLimit = null,
        fupDownMbps = null,
        fupUpMbps = null,
        serviceTypes = setOf("PPPOE"),
    )
}

private class PlanChgFakeAccessRepo(
    private val subscriptionId: UUID,
    private val akun: List<SubscriberAccess>,
) : SubscriberAccessRepository {
    val saved = mutableListOf<SubscriberAccess>()
    override fun save(access: SubscriberAccess): SubscriberAccess {
        saved += access
        return access
    }

    override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> =
        if (subscriptionId == this.subscriptionId) akun else emptyList()

    override fun findActiveOnNas(): List<SubscriberAccess> = notUsed()
    override fun findIsolatedOnNas(): List<SubscriberAccess> = notUsed()
    override fun findActiveMacUsernames(): List<String> = notUsed()
    override fun findById(id: UUID): SubscriberAccess? = akun.firstOrNull { it.id == id }
    override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByCustomerIds(customerIds: Collection<UUID>): List<SubscriberAccess> = notUsed()
    override fun findByUsername(username: String): SubscriberAccess? = notUsed()
    override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
    override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = notUsed()
    override fun countByNasId(nasId: UUID): Long = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    override fun findAll(): List<SubscriberAccess> = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class PlanChgFakeActionRepo : BngActionRepository {
    val saved = mutableListOf<BngAction>()
    override fun save(action: BngAction): BngAction {
        saved += action
        return action
    }

    override fun findById(id: UUID): BngAction? = saved.firstOrNull { it.id == id }
    override fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction> = emptyList()
    override fun findServerProvisioningPending(limit: Int): List<BngAction> = emptyList()
    override fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int): List<BngAction> = emptyList()
    override fun findAccessIdsWithPendingProvisioning(subscriberAccessIds: Collection<UUID>): Set<UUID> = emptySet()
}

private class PlanChgFakeCatalogApi(private val plans: Map<UUID, PlanNetworkRef?>) : CatalogApi {
    override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = plans[planId]
    override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = throw UnsupportedOperationException()
    override fun findPlanByName(name: String) = throw UnsupportedOperationException()
    override fun findActivePlans() = throw UnsupportedOperationException()
}
