package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusProvisioningPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.BngActionService
import com.duluin.ftth.bng.application.service.IsolirReconcileRunner
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.RadiusGroups
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Menguji penyelaras isolir ([IsolirReconcileRunner]) dengan fake murni (tanpa Spring/DB):
 * grup RADIUS yang sesungguhnya berlaku dibandingkan dengan status yang dicatat aplikasi,
 * lalu perbaikannya diantrekan lewat jalur aksi yang sama dengan tombol Isolir/Pulihkan.
 *
 * Yang dijaga di sini adalah dua kesalahan yang mahal di lapangan: pelanggan yang sudah
 * membayar tapi tetap terkurung halaman tagihan, dan penunggak yang tetap online penuh.
 */
class IsolirReconcileRunnerTest {

    private val tenantId: UUID = UuidV7.generate()
    private val slug = "demo"

    @Test
    fun `akun ISOLATED yang grup RADIUS-nya masih grup paket diisolir ulang`() {
        val a = access(status = AccessStatus.ISOLATED)
        val f = fixture(accesses = listOf(a), groups = mapOf("$slug:${a.username}" to RadiusGroups.normal(a.planId)))

        f.runner.execute(tenantId, slug)

        val provision = f.actionRepo.saved.single { it.action == BngActionType.PROVISION }
        assertThat(provision.groupname).isEqualTo(RadiusGroups.ISOLIR)
        assertThat(provision.subscriberAccessId).isEqualTo(a.id)
        // DISCONNECT wajib menyertainya: sesi lama tetap online penuh sampai ia mati.
        assertThat(f.actionRepo.saved.map { it.action }).contains(BngActionType.DISCONNECT)
    }

    @Test
    fun `akun ACTIVE yang masih tersangkut grup isolir dipulihkan ke grup paketnya`() {
        val a = access(status = AccessStatus.ACTIVE)
        val f = fixture(accesses = listOf(a), groups = mapOf("$slug:${a.username}" to RadiusGroups.ISOLIR))

        f.runner.execute(tenantId, slug)

        val provision = f.actionRepo.saved.single { it.action == BngActionType.PROVISION }
        assertThat(provision.groupname).isEqualTo(RadiusGroups.normal(a.planId))
        // Grup paket ikut disinkronkan ulang — pelanggan bisa kembali setelah paketnya diubah.
        assertThat(f.actionRepo.saved.map { it.action }).contains(BngActionType.SYNC_GROUP, BngActionType.DISCONNECT)
    }

    @Test
    fun `akun yang grup RADIUS-nya sudah sesuai tak diapa-apakan`() {
        val aktif = access(status = AccessStatus.ACTIVE)
        val isolir = access(status = AccessStatus.ISOLATED)
        val f = fixture(
            accesses = listOf(aktif, isolir),
            groups = mapOf(
                "$slug:${aktif.username}" to RadiusGroups.normal(aktif.planId),
                "$slug:${isolir.username}" to RadiusGroups.ISOLIR,
            ),
        )

        f.runner.execute(tenantId, slug)

        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `akun yang belum punya baris di RADIUS dilewati, bukan diprovisikan`() {
        // Diisolir sebelum instalasinya rampung: tak ada baris grup sama sekali. Membuatkannya
        // di sini sama dengan menyerahkan login yang sengaja belum diberikan.
        val a = access(status = AccessStatus.ISOLATED)
        val f = fixture(accesses = listOf(a), groups = emptyMap())

        f.runner.execute(tenantId, slug)

        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `akun ACTIVE di grup FUP bukan penyimpangan`() {
        val a = access(status = AccessStatus.ACTIVE)
        val f = fixture(accesses = listOf(a), groups = mapOf("$slug:${a.username}" to RadiusGroups.fup(a.planId)))

        f.runner.execute(tenantId, slug)

        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `akun yang perbaikannya masih dalam perjalanan tak diantre ulang`() {
        val a = access(status = AccessStatus.ISOLATED)
        val f = fixture(
            accesses = listOf(a),
            groups = mapOf("$slug:${a.username}" to RadiusGroups.normal(a.planId)),
            inFlight = setOf(a.id),
        )

        f.runner.execute(tenantId, slug)

        assertThat(f.actionRepo.saved).isEmpty()
    }

    @Test
    fun `akun DHCP dicocokkan dengan MAC polos tanpa prefiks kode tenant`() {
        val a = access(status = AccessStatus.ISOLATED, authType = AuthType.DHCP, username = "aa:bb:cc:dd:ee:ff")
        val f = fixture(accesses = listOf(a), groups = mapOf(a.username to RadiusGroups.normal(a.planId)))

        f.runner.execute(tenantId, slug)

        // Salah memetakan identitas = seluruh akun tampak "belum diprovisikan" dan penyelaras
        // diam-diam tak pernah memperbaiki apa pun.
        assertThat(f.radius.asked).containsExactly(a.username)
        assertThat(f.actionRepo.saved.map { it.action }).contains(BngActionType.PROVISION)
    }

    @Test
    fun `tenant tanpa akun ber-BRAS tak menyentuh radius-db sama sekali`() {
        val f = fixture(accesses = emptyList(), groups = emptyMap())

        f.runner.execute(tenantId, slug)

        assertThat(f.radius.calls).isZero()
        assertThat(f.actionRepo.saved).isEmpty()
    }

    // ---- Fixture & fake ----

    private class Fixture(
        val runner: IsolirReconcileRunner,
        val actionRepo: IsolirFakeActionRepo,
        val radius: IsolirFakeRadiusPort,
    )

    private fun fixture(
        accesses: List<SubscriberAccess>,
        groups: Map<String, String>,
        inFlight: Set<UUID> = emptySet(),
    ): Fixture {
        val accessRepo = IsolirFakeAccessRepo(accesses)
        val actionRepo = IsolirFakeActionRepo(inFlight)
        val radius = IsolirFakeRadiusPort(groups)
        val catalog = IsolirFakeCatalogApi(accesses.associate { it.planId to planNet(it.planId) })
        val runner = IsolirReconcileRunner(
            IsolirFakeTenantApi(tenantId, slug),
            accessRepo,
            actionRepo,
            radius,
            catalog,
            BngActionService(actionRepo, accessRepo),
        )
        return Fixture(runner, actionRepo, radius)
    }

    private fun access(
        status: AccessStatus,
        authType: AuthType = AuthType.PPPOE,
        username: String = "u${UUID.randomUUID().toString().take(6)}",
    ) = SubscriberAccess.create(
        tenantId = tenantId,
        subscriptionId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        username = username,
        secret = "rahasia123",
        planId = UuidV7.generate(),
        nasId = UuidV7.generate(),
        status = status,
        authType = authType,
    )

    private fun planNet(planId: UUID) = PlanNetworkRef(
        planId = planId,
        name = "Paket Uji",
        downMbps = 50,
        upMbps = 10,
        rateLimit = "10M/50M",
        connectionLimit = null,
        fupEnabled = false,
        fupQuotaMb = null,
        fupRateLimit = null,
        fupDownMbps = null,
        fupUpMbps = null,
        serviceTypes = setOf("PPPOE"),
    )
}

private class IsolirFakeAccessRepo(private val accesses: List<SubscriberAccess>) : SubscriberAccessRepository {
    override fun findActiveOnNas(): List<SubscriberAccess> = accesses.filter { it.status == AccessStatus.ACTIVE }
    override fun findIsolatedOnNas(): List<SubscriberAccess> = accesses.filter { it.status == AccessStatus.ISOLATED }
    override fun findById(id: UUID): SubscriberAccess? = accesses.firstOrNull { it.id == id }
    override fun save(access: SubscriberAccess): SubscriberAccess = access
    override fun findActiveMacUsernames(): List<String> = notUsed()
    override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByCustomerIds(customerIds: Collection<UUID>): List<SubscriberAccess> = notUsed()
    override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByUsername(username: String): SubscriberAccess? = notUsed()
    override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
    override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = notUsed()
    override fun countByNasId(nasId: UUID): Long = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    override fun findAll(): List<SubscriberAccess> = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class IsolirFakeActionRepo(private val inFlight: Set<UUID>) : BngActionRepository {
    val saved = mutableListOf<BngAction>()
    override fun save(action: BngAction): BngAction {
        saved += action
        return action
    }

    override fun findAccessIdsWithPendingProvisioning(subscriberAccessIds: Collection<UUID>): Set<UUID> =
        inFlight.intersect(subscriberAccessIds.toSet())

    override fun findById(id: UUID): BngAction? = saved.firstOrNull { it.id == id }
    override fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction> = emptyList()
    override fun findServerProvisioningPending(limit: Int): List<BngAction> = emptyList()
    override fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int): List<BngAction> = emptyList()
}

private class IsolirFakeRadiusPort(private val groups: Map<String, String>) : RadiusProvisioningPort {
    /** Identitas yang benar-benar ditanyakan ke radius-db — inti uji pemetaan prefiks. */
    val asked = mutableListOf<String>()
    var calls = 0

    override fun groupsOf(tenantId: UUID, scopedUsernames: Collection<String>): Map<String, String> {
        calls++
        asked += scopedUsernames
        return groups.filterKeys { it in scopedUsernames }
    }

    override fun isConfigured(): Boolean = true
    override fun provision(tenantId: UUID, scopedUsername: String, password: String, groupname: String, framedIp: String?) =
        notUsed()

    override fun deprovision(tenantId: UUID, scopedUsername: String) = notUsed()
    override fun syncGroup(
        tenantId: UUID,
        groupname: String,
        rateLimit: String,
        simultaneousUse: Int?,
        fupGroupname: String?,
        fupRateLimit: String?,
    ) = notUsed()

    override fun ensureIsolirGroup(tenantId: UUID, rateLimit: String, addressList: String) = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class IsolirFakeCatalogApi(private val plans: Map<UUID, PlanNetworkRef>) : CatalogApi {
    override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = plans[planId]
    override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = throw UnsupportedOperationException()
    override fun findPlanByName(name: String) = throw UnsupportedOperationException()
    override fun findActivePlans() = throw UnsupportedOperationException()
}

private class IsolirFakeTenantApi(private val id: UUID, private val slug: String) : TenantApi {
    override fun findById(id: UUID): TenantRef? =
        if (id == this.id) TenantRef(id, slug, "Tenant", TenantStatus.ACTIVE) else null

    override fun findBySlug(slug: String): TenantRef? = notUsed()
    override fun requireById(id: UUID): TenantRef = notUsed()
    override fun platformTenantId(): UUID = notUsed()
    override fun findActiveTenantIds(): List<UUID> = listOf(id)
    override fun ensureTenant(slug: String, name: String): TenantRef = notUsed()
    override fun suspend(id: UUID): TenantRef = notUsed()
    override fun activate(id: UUID): TenantRef = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}
