package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.BngActionService
import com.duluin.ftth.bng.application.service.FupCycleRunner
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.RadiusGroups
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.bng.domain.model.TrafficSample
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanCommercialRef
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Menguji mesin FUP ([FupCycleRunner]) dengan fake murni (tanpa Spring/DB): tiap akun
 * ACTIVE ber-BRAS pada paket ber-FUP dibandingkan pemakaian periodenya dengan kuota,
 * lalu di-throttle / dipulihkan tepat sekali. Memakai [FupCycleRunner.enforce] dengan
 * awal-periode tetap agar bebas dari jam dinding.
 */
class FupCycleRunnerTest {

    // Kuota 100 MB → 100_000_000 byte. "Over" = 200 juta, "under" = 10 juta.
    private val quotaMb = 100L
    private val periodStart: Instant = Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun `melewati kuota dan belum throttle memicu applyFup lalu menandai bendera`() {
        val planId = UuidV7.generate()
        val a = access(planId)
        val fixture = fixture(candidates = listOf(a), usage = mapOf(a.id to 200_000_000L), plan = planNet(planId))

        fixture.runner.enforce(periodStart)

        assertThat(a.fupThrottled).isTrue()
        assertThat(fixture.accessRepo.saved).contains(a)
        // Dua aksi: PROVISION me-remap keanggotaan grup ke grup FUP + CoA menurunkan sesi hidup.
        val provision = fixture.actionRepo.saved.single { it.action == BngActionType.PROVISION }
        assertThat(provision.groupname).isEqualTo(RadiusGroups.fup(planId))
        val coa = fixture.actionRepo.saved.single { it.action == BngActionType.COA }
        assertThat(coa.downMbps).isEqualTo(5)
        assertThat(coa.upMbps).isEqualTo(2)
    }

    @Test
    fun `pemakaian turun saat masih throttle memicu clearFup lalu mencabut bendera`() {
        val planId = UuidV7.generate()
        val a = access(planId, throttled = true)
        val fixture = fixture(candidates = listOf(a), usage = mapOf(a.id to 10_000_000L), plan = planNet(planId))

        fixture.runner.enforce(periodStart)

        assertThat(a.fupThrottled).isFalse()
        // PROVISION mengembalikan ke grup normal + CoA memulihkan kecepatan penuh.
        val provision = fixture.actionRepo.saved.single { it.action == BngActionType.PROVISION }
        assertThat(provision.groupname).isEqualTo(RadiusGroups.normal(planId))
        val coa = fixture.actionRepo.saved.single { it.action == BngActionType.COA }
        assertThat(coa.downMbps).isEqualTo(50)
        assertThat(coa.upMbps).isEqualTo(10)
    }

    @Test
    fun `di bawah kuota tanpa throttle tak melakukan apa-apa`() {
        val planId = UuidV7.generate()
        val a = access(planId)
        val fixture = fixture(candidates = listOf(a), usage = mapOf(a.id to 10_000_000L), plan = planNet(planId))

        fixture.runner.enforce(periodStart)

        assertThat(a.fupThrottled).isFalse()
        assertThat(fixture.actionRepo.saved).isEmpty()
    }

    @Test
    fun `melewati kuota tapi sudah throttle tak mengantre ulang`() {
        val planId = UuidV7.generate()
        val a = access(planId, throttled = true)
        val fixture = fixture(candidates = listOf(a), usage = mapOf(a.id to 500_000_000L), plan = planNet(planId))

        fixture.runner.enforce(periodStart)

        assertThat(a.fupThrottled).isTrue()
        assertThat(fixture.actionRepo.saved).isEmpty()
    }

    @Test
    fun `paket tanpa FUP diabaikan meski pemakaian tinggi`() {
        val planId = UuidV7.generate()
        val a = access(planId)
        val nonFup = planNet(planId).copy(fupEnabled = false, fupQuotaMb = null, fupRateLimit = null, fupDownMbps = null, fupUpMbps = null)
        val fixture = fixture(candidates = listOf(a), usage = mapOf(a.id to 999_000_000L), plan = nonFup)

        fixture.runner.enforce(periodStart)

        assertThat(a.fupThrottled).isFalse()
        assertThat(fixture.actionRepo.saved).isEmpty()
    }

    @Test
    fun `paket ber-FUP tanpa kuota diabaikan`() {
        val planId = UuidV7.generate()
        val a = access(planId)
        val noQuota = planNet(planId).copy(fupQuotaMb = null)
        val fixture = fixture(candidates = listOf(a), usage = mapOf(a.id to 999_000_000L), plan = noQuota)

        fixture.runner.enforce(periodStart)

        assertThat(a.fupThrottled).isFalse()
        assertThat(fixture.actionRepo.saved).isEmpty()
    }

    @Test
    fun `paket tak ditemukan di katalog diabaikan`() {
        val planId = UuidV7.generate()
        val a = access(planId)
        val fixture = fixture(candidates = listOf(a), usage = mapOf(a.id to 999_000_000L), plan = null)

        fixture.runner.enforce(periodStart)

        assertThat(a.fupThrottled).isFalse()
        assertThat(fixture.actionRepo.saved).isEmpty()
    }

    @Test
    fun `dalam satu batch hanya akun yang melewati kuota yang di-throttle`() {
        val planId = UuidV7.generate()
        val over = access(planId)
        val under = access(planId)
        val fixture = fixture(
            candidates = listOf(over, under),
            usage = mapOf(over.id to 200_000_000L, under.id to 10_000_000L),
            plan = planNet(planId),
        )

        fixture.runner.enforce(periodStart)

        assertThat(over.fupThrottled).isTrue()
        assertThat(under.fupThrottled).isFalse()
        // Semua aksi yang terantre milik akun over saja.
        assertThat(fixture.actionRepo.saved.map { it.subscriberAccessId }).containsOnly(over.id)
    }

    // ---- Fixture & fake ----

    private class Fixture(
        val runner: FupCycleRunner,
        val accessRepo: FakeSubscriberAccessRepo,
        val actionRepo: FakeBngActionRepo,
    )

    private fun fixture(candidates: List<SubscriberAccess>, usage: Map<UUID, Long>, plan: PlanNetworkRef?): Fixture {
        val accessRepo = FakeSubscriberAccessRepo(candidates)
        val actionRepo = FakeBngActionRepo()
        val plans = candidates.associate { it.planId to plan }
        val bngActions = BngActionService(actionRepo, accessRepo)
        val runner = FupCycleRunner(accessRepo, FakeAccountingRepo(usage), FakeCatalogApi(plans), bngActions)
        return Fixture(runner, accessRepo, actionRepo)
    }

    private fun access(planId: UUID, throttled: Boolean = false): SubscriberAccess {
        val a = SubscriberAccess.create(
            tenantId = UuidV7.generate(),
            subscriptionId = UuidV7.generate(),
            customerId = UuidV7.generate(),
            username = "u${UUID.randomUUID().toString().take(6)}",
            secret = "rahasia123",
            planId = planId,
            nasId = UuidV7.generate(),
            status = AccessStatus.ACTIVE,
        )
        if (throttled) a.applyFupThrottle()
        return a
    }

    private fun planNet(planId: UUID) = PlanNetworkRef(
        planId = planId,
        name = "Paket Uji",
        downMbps = 50,
        upMbps = 10,
        rateLimit = "10M/50M",
        connectionLimit = null,
        fupEnabled = true,
        fupQuotaMb = quotaMb,
        fupRateLimit = "2M/5M",
        fupDownMbps = 5,
        fupUpMbps = 2,
    )
}

private class FakeSubscriberAccessRepo(private val active: List<SubscriberAccess>) : SubscriberAccessRepository {
    val saved = mutableListOf<SubscriberAccess>()
    override fun save(access: SubscriberAccess): SubscriberAccess {
        saved += access
        return access
    }

    override fun findActiveOnNas(): List<SubscriberAccess> = active
    override fun findById(id: UUID): SubscriberAccess? = active.firstOrNull { it.id == id }
    override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = notUsed()
    override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByUsername(username: String): SubscriberAccess? = notUsed()
    override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
    override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = notUsed()
    override fun countByNasId(nasId: UUID): Long = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class FakeAccountingRepo(private val usage: Map<UUID, Long>) : AccountingRecordRepository {
    override fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long> =
        usage.filterKeys { it in subscriberAccessIds }

    override fun saveAll(points: List<AccountingRecordPoint>): Unit = throw UnsupportedOperationException()
    override fun trafficSince(subscriberAccessId: UUID, since: Instant): List<TrafficSample> =
        throw UnsupportedOperationException()
}

private class FakeCatalogApi(private val plans: Map<UUID, PlanNetworkRef?>) : CatalogApi {
    override fun findPlanNetwork(planId: UUID): PlanNetworkRef? = plans[planId]
    override fun findPlanCommercial(planId: UUID): PlanCommercialRef? = throw UnsupportedOperationException()
}

private class FakeBngActionRepo : BngActionRepository {
    val saved = mutableListOf<BngAction>()
    override fun save(action: BngAction): BngAction {
        saved += action
        return action
    }

    override fun findById(id: UUID): BngAction? = saved.firstOrNull { it.id == id }
    override fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction> = emptyList()
    override fun findServerProvisioningPending(limit: Int): List<BngAction> = emptyList()
}
