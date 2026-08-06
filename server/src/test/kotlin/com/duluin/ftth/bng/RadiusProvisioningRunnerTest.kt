package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusProvisioningPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.RadiusProvisioningRunner
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionStatus
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Menguji worker jalur-TULIS RADIUS ([RadiusProvisioningRunner]) dengan fake murni
 * (tanpa Spring/DB/radius-db): mengklaim aksi PROVISIONING yang PENDING, meng-eksekusi ke
 * [RadiusProvisioningPort], dan menandai COMPLETED. Inti yang diuji: kode tenant (`slug`)
 * di-PREFIX ke username akun (`"{slug}:{username}"` — kunci isolasi multi-tenant di
 * radius-db) sementara nama grup (`plan:{uuid}`) TIDAK di-prefix; password diresolusi dari
 * akun; kegagalan transien tetap PENDING (diulang) sampai lewat batas usia → baru FAILED.
 * Memakai [RadiusProvisioningRunner.execute] dengan jam tetap agar bebas jam dinding.
 */
class RadiusProvisioningRunnerTest {

    private val tenantId = UuidV7.generate()
    private val nasId = UuidV7.generate()
    private val slug = "acme"
    private val now: Instant = Instant.parse("2026-07-29T00:00:00Z")

    @Test
    fun `PROVISION memprefiks username dengan slug, meresolusi password, dan menuntaskan aksi`() {
        val access = access(username = "budi", secret = "rahasia123")
        val action = BngAction.provision(
            tenantId, access.id, nasId, username = "budi", groupname = "plan:${access.planId}",
            requestedBy = null, requestedByEmail = null, at = now,
        )
        val fixture = fixture(pending = listOf(action), accesses = listOf(access))

        fixture.runner.execute(tenantId, now)

        val call = fixture.radius.provisions.single()
        assertThat(call.scopedUsername).isEqualTo("acme:budi")
        assertThat(call.password).isEqualTo("rahasia123")
        assertThat(call.groupname).isEqualTo("plan:${access.planId}")
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
        assertThat(fixture.actions.saved).contains(action)
    }

    @Test
    fun `PROVISION DHCP memakai MAC tanpa prefix slug dan meneruskan Framed-IP-Address`() {
        val access = SubscriberAccess.create(
            tenantId = tenantId,
            subscriptionId = UuidV7.generate(),
            customerId = UuidV7.generate(),
            username = "AA:BB:CC:DD:EE:FF",
            secret = "",
            planId = UuidV7.generate(),
            nasId = nasId,
            status = AccessStatus.ACTIVE,
            authType = AuthType.DHCP,
            framedIp = "100.64.0.10",
        )
        val action = BngAction.provision(
            tenantId, access.id, nasId, username = access.username, groupname = "plan:${access.planId}",
            requestedBy = null, requestedByEmail = null, authType = AuthType.DHCP, at = now,
        )
        val fixture = fixture(pending = listOf(action), accesses = listOf(access))

        fixture.runner.execute(tenantId, now)

        val call = fixture.radius.provisions.single()
        // MAC global-unik → TAK di-prefix slug (beda dari PPPoE/Hotspot).
        assertThat(call.scopedUsername).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(call.password).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(call.framedIp).isEqualTo("100.64.0.10")
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
    }

    @Test
    fun `DEPROVISION memprefiks username lalu menuntaskan aksi`() {
        val action = BngAction.deprovision(
            tenantId, nasId, username = "budi", requestedBy = null, requestedByEmail = null, at = now,
        )
        val fixture = fixture(pending = listOf(action))

        fixture.runner.execute(tenantId, now)

        assertThat(fixture.radius.deprovisions.single()).isEqualTo("acme:budi")
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
    }

    @Test
    fun `SYNC_GROUP meneruskan nama grup TANPA prefix beserta rate-limit dan grup FUP`() {
        val planId = UuidV7.generate()
        val action = BngAction.syncGroup(
            tenantId, nasId,
            groupname = "plan:$planId", rateLimit = "10M/50M", simultaneousUse = 1,
            fupGroupname = "plan:$planId:fup", fupRateLimit = "2M/5M",
            requestedBy = null, requestedByEmail = null, at = now,
        )
        val fixture = fixture(pending = listOf(action))

        fixture.runner.execute(tenantId, now)

        val call = fixture.radius.syncs.single()
        // Grup pakai UUID → sudah unik lintas-tenant, TAK di-prefix slug.
        assertThat(call.groupname).isEqualTo("plan:$planId")
        assertThat(call.rateLimit).isEqualTo("10M/50M")
        assertThat(call.simultaneousUse).isEqualTo(1)
        assertThat(call.fupGroupname).isEqualTo("plan:$planId:fup")
        assertThat(call.fupRateLimit).isEqualTo("2M/5M")
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
    }

    @Test
    fun `gagal transien di bawah batas usia tetap PENDING agar diulang`() {
        val access = access(username = "budi", secret = "rahasia123")
        // requestedAt = now (usia 0) < maxRetry (1 jam) → transien.
        val action = BngAction.provision(
            tenantId, access.id, nasId, username = "budi", groupname = "plan:${access.planId}",
            requestedBy = null, requestedByEmail = null, at = now,
        )
        val fixture = fixture(pending = listOf(action), accesses = listOf(access), radiusFailure = RuntimeException("radius-db mati"))

        fixture.runner.execute(tenantId, now)

        assertThat(action.status).isEqualTo(BngActionStatus.PENDING)
        assertThat(action.isTerminal).isFalse()
        assertThat(action.detail).contains("radius-db mati")
        assertThat(fixture.actions.saved).contains(action)
    }

    @Test
    fun `gagal melewati batas usia ditandai FAILED (menyerah)`() {
        val access = access(username = "budi", secret = "rahasia123")
        // requestedAt dua jam lalu → melewati maxRetry (1 jam) → terminal FAILED.
        val old = now.minusSeconds(2 * 3600)
        val action = BngAction.provision(
            tenantId, access.id, nasId, username = "budi", groupname = "plan:${access.planId}",
            requestedBy = null, requestedByEmail = null, at = old,
        )
        val fixture = fixture(pending = listOf(action), accesses = listOf(access), radiusFailure = RuntimeException("radius-db mati"))

        fixture.runner.execute(tenantId, now)

        assertThat(action.status).isEqualTo(BngActionStatus.FAILED)
        assertThat(action.detail).contains("radius-db mati")
    }

    @Test
    fun `PROVISION dengan password tak terbaca tak menyentuh radius dan tetap PENDING`() {
        // subscriberAccessId menunjuk akun yang tak ada di repo → resolvePassword gagal.
        val action = BngAction.provision(
            tenantId, UuidV7.generate(), nasId, username = "budi", groupname = "plan:x",
            requestedBy = null, requestedByEmail = null, at = now,
        )
        val fixture = fixture(pending = listOf(action))

        fixture.runner.execute(tenantId, now)

        assertThat(fixture.radius.provisions).isEmpty()
        assertThat(action.status).isEqualTo(BngActionStatus.PENDING)
        assertThat(action.detail).contains("password")
    }

    @Test
    fun `tenant tanpa slug dilewati tanpa menyentuh radius`() {
        val action = BngAction.deprovision(
            tenantId, nasId, username = "budi", requestedBy = null, requestedByEmail = null, at = now,
        )
        val fixture = fixture(pending = listOf(action), slug = null)

        fixture.runner.execute(tenantId, now)

        assertThat(fixture.radius.deprovisions).isEmpty()
        assertThat(fixture.actions.saved).isEmpty()
        assertThat(action.status).isEqualTo(BngActionStatus.PENDING)
    }

    // ---- Fixture & fake ----

    private class Fixture(
        val runner: RadiusProvisioningRunner,
        val actions: FakeActionRepo,
        val radius: FakeRadiusPort,
    )

    private fun fixture(
        pending: List<BngAction>,
        accesses: List<SubscriberAccess> = emptyList(),
        radiusFailure: Exception? = null,
        slug: String? = this.slug,
    ): Fixture {
        val actions = FakeActionRepo(pending)
        val radius = FakeRadiusPort(radiusFailure)
        val runner = RadiusProvisioningRunner(
            FakeTenantApi(tenantId, slug),
            actions,
            FakeAccessRepo(accesses),
            radius,
            RadiusProperties(),
        )
        return Fixture(runner, actions, radius)
    }

    private fun access(username: String, secret: String) = SubscriberAccess.create(
        tenantId = tenantId,
        subscriptionId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        username = username,
        secret = secret,
        planId = UuidV7.generate(),
        nasId = nasId,
        status = AccessStatus.ACTIVE,
    )
}

private class FakeTenantApi(private val id: UUID, private val slug: String?) : TenantApi {
    override fun findById(id: UUID): TenantRef? =
        slug?.takeIf { id == this.id }?.let { TenantRef(id, it, "Tenant", TenantStatus.ACTIVE) }

    override fun findBySlug(slug: String): TenantRef? = notUsed()
    override fun requireById(id: UUID): TenantRef = notUsed()
    override fun platformTenantId(): UUID = notUsed()
    override fun findActiveTenantIds(): List<UUID> = listOf(id)
    override fun ensureTenant(slug: String, name: String): TenantRef = notUsed()
    override fun suspend(id: UUID): TenantRef = notUsed()
    override fun activate(id: UUID): TenantRef = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class FakeActionRepo(private val pending: List<BngAction>) : BngActionRepository {
    val saved = mutableListOf<BngAction>()
    override fun save(action: BngAction): BngAction {
        saved += action
        return action
    }

    override fun findById(id: UUID): BngAction? = pending.firstOrNull { it.id == id }
    override fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction> = emptyList()
    override fun findServerProvisioningPending(limit: Int): List<BngAction> = pending.take(limit)
    override fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int): List<BngAction> = emptyList()
}

private class FakeAccessRepo(private val accesses: List<SubscriberAccess>) : SubscriberAccessRepository {
    override fun findById(id: UUID): SubscriberAccess? = accesses.firstOrNull { it.id == id }
    override fun save(access: SubscriberAccess): SubscriberAccess = notUsed()
    override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = notUsed()
    override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByUsername(username: String): SubscriberAccess? = notUsed()
    override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
    override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
    override fun findActiveOnNas(): List<SubscriberAccess> = notUsed()
    override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = notUsed()
    override fun countByNasId(nasId: UUID): Long = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    override fun findAll(): List<com.duluin.ftth.bng.domain.model.SubscriberAccess> = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class FakeRadiusPort(private val failWith: Exception?) : RadiusProvisioningPort {
    data class ProvisionCall(
        val tenantId: UUID,
        val scopedUsername: String,
        val password: String,
        val groupname: String,
        val framedIp: String?,
    )
    data class SyncCall(
        val tenantId: UUID,
        val groupname: String,
        val rateLimit: String,
        val simultaneousUse: Int?,
        val fupGroupname: String?,
        val fupRateLimit: String?,
    )

    val provisions = mutableListOf<ProvisionCall>()
    val deprovisions = mutableListOf<String>()
    val syncs = mutableListOf<SyncCall>()

    override fun isConfigured(): Boolean = true

    override fun provision(tenantId: UUID, scopedUsername: String, password: String, groupname: String, framedIp: String?) {
        failWith?.let { throw it }
        provisions += ProvisionCall(tenantId, scopedUsername, password, groupname, framedIp)
    }

    override fun deprovision(tenantId: UUID, scopedUsername: String) {
        failWith?.let { throw it }
        deprovisions += scopedUsername
    }

    override fun syncGroup(
        tenantId: UUID,
        groupname: String,
        rateLimit: String,
        simultaneousUse: Int?,
        fupGroupname: String?,
        fupRateLimit: String?,
    ) {
        failWith?.let { throw it }
        syncs += SyncCall(tenantId, groupname, rateLimit, simultaneousUse, fupGroupname, fupRateLimit)
    }
}
