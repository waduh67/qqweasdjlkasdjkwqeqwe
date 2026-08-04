package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionControlPort
import com.duluin.ftth.bng.application.service.RadiusSessionControlRunner
import com.duluin.ftth.bng.config.RadiusProperties
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionStatus
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.NasReachability
import com.duluin.ftth.bng.domain.model.NasVendor
import com.duluin.ftth.bng.domain.model.SessionObservation
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Menguji worker jalur-KONTROL sesi RADIUS server-side ([RadiusSessionControlRunner]) dengan
 * fake murni (tanpa Spring/DB/UDP). Inti yang diuji adalah reachability 3-jalur RADIUS-as-a-
 * service: DIRECT/VPN menembak DAE ke nas.address (dan menangani sesi mati per jenis aksi),
 * NONE degradasi anggun (COMPLETED bercatatan, tanpa menembak), pembelahan-klaim menyisihkan
 * BRAS COLLECTOR, sesi `radacct` dibaca malas, dan kegagalan transien tetap PENDING sampai
 * lewat batas usia.
 */
class RadiusSessionControlRunnerTest {

    private val tenantId = UuidV7.generate()
    private val slug = "acme"
    private val now: Instant = Instant.parse("2026-07-30T00:00:00Z")

    @Test
    fun `DIRECT DISCONNECT dengan sesi hidup menembak DAE dan menuntaskan aksi`() {
        val nas = nas(NasReachability.DIRECT)
        val action = disconnect(nas.id, "budi")
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = listOf(session("budi")))

        fx.runner.execute(tenantId, now)

        val call = fx.control.disconnects.single()
        assertThat(call.host).isEqualTo("203.0.113.9")
        assertThat(call.secret).isEqualTo("s3cr3t")
        assertThat(call.username).isEqualTo("budi")
        assertThat(call.acctSessionId).isEqualTo("s-budi")
        assertThat(call.nasIp).isEqualTo("10.20.0.1")
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
    }

    @Test
    fun `DIRECT DISCONNECT tanpa sesi tak menembak dan tetap tuntas (target tercapai)`() {
        val nas = nas(NasReachability.DIRECT)
        val action = disconnect(nas.id, "hantu")
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = emptyList())

        fx.runner.execute(tenantId, now)

        assertThat(fx.control.disconnects).isEmpty()
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
        assertThat(action.detail).isNull() // tuntas polos, bukan degradasi
    }

    @Test
    fun `DIRECT COA dengan sesi hidup menembak CoA dengan rate baru`() {
        val nas = nas(NasReachability.DIRECT)
        val action = coa(nas.id, "budi", down = 100, up = 30)
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = listOf(session("budi")))

        fx.runner.execute(tenantId, now)

        val call = fx.control.coas.single()
        assertThat(call.username).isEqualTo("budi")
        assertThat(call.downMbps).isEqualTo(100)
        assertThat(call.upMbps).isEqualTo(30)
        assertThat(call.acctSessionId).isEqualTo("s-budi")
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
    }

    @Test
    fun `DIRECT COA tanpa sesi degradasi anggun (tak menembak, catatan login ulang)`() {
        val nas = nas(NasReachability.DIRECT)
        val action = coa(nas.id, "hantu", down = 100, up = 30)
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = emptyList())

        fx.runner.execute(tenantId, now)

        assertThat(fx.control.coas).isEmpty()
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
        assertThat(action.detail).contains("login ulang")
    }

    @Test
    fun `VPN DISCONNECT dengan sesi hidup menembak DAE ke alamat overlay (S2c)`() {
        val nas = nas(NasReachability.VPN)
        val action = disconnect(nas.id, "budi")
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = listOf(session("budi")))

        fx.runner.execute(tenantId, now)

        // Identik jalur DIRECT: DAE ditembak ke nas.address (yang untuk VPN diisi IP overlay).
        val call = fx.control.disconnects.single()
        assertThat(call.host).isEqualTo("203.0.113.9")
        assertThat(call.username).isEqualTo("budi")
        assertThat(call.acctSessionId).isEqualTo("s-budi")
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
        assertThat(fx.radacct.calls).isNotEmpty() // VPN kini ikut memicu baca sesi
    }

    @Test
    fun `VPN COA tanpa sesi degradasi anggun (berlaku saat login ulang)`() {
        val nas = nas(NasReachability.VPN)
        val action = coa(nas.id, "hantu", down = 100, up = 30)
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = emptyList())

        fx.runner.execute(tenantId, now)

        assertThat(fx.control.coas).isEmpty()
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
        assertThat(action.detail).contains("login ulang")
    }

    @Test
    fun `NONE COA degradasi anggun (tak terjangkau)`() {
        val nas = nas(NasReachability.NONE)
        val action = coa(nas.id, "budi", down = 50, up = 10)
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = emptyList())

        fx.runner.execute(tenantId, now)

        assertThat(fx.control.coas).isEmpty()
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
        assertThat(action.detail).contains("login ulang")
    }

    @Test
    fun `BRAS COLLECTOR disisihkan dari klaim server (pembelahan-klaim)`() {
        val collectorNas = nas(NasReachability.COLLECTOR)
        val directNas = nas(NasReachability.DIRECT)
        val onCollector = disconnect(collectorNas.id, "budi")
        val onDirect = disconnect(directNas.id, "budi")
        val fx = fixture(
            nas = listOf(collectorNas, directNas),
            pending = listOf(onCollector, onDirect),
            sessions = listOf(session("budi")),
        )

        fx.runner.execute(tenantId, now)

        // Hanya BRAS non-COLLECTOR ditanyakan ke antrean.
        assertThat(fx.actions.sessionControlNasIds).containsExactly(directNas.id)
        assertThat(onCollector.status).isEqualTo(BngActionStatus.PENDING) // tak disentuh server
        assertThat(onDirect.status).isEqualTo(BngActionStatus.COMPLETED)
    }

    @Test
    fun `sesi radacct tak dibaca bila tak ada aksi DIRECT atau VPN (degradasi murni)`() {
        val nas = nas(NasReachability.NONE)
        val action = disconnect(nas.id, "budi")
        val fx = fixture(nas = listOf(nas), pending = listOf(action), sessions = emptyList())

        fx.runner.execute(tenantId, now)

        assertThat(fx.radacct.calls).isEmpty() // jalur degradasi NONE tak butuh sesi
        assertThat(action.status).isEqualTo(BngActionStatus.COMPLETED)
    }

    @Test
    fun `kegagalan DAE transien di bawah batas usia tetap PENDING agar diulang`() {
        val nas = nas(NasReachability.DIRECT)
        val action = disconnect(nas.id, "budi", at = now) // usia 0 < maxRetry
        val fx = fixture(
            nas = listOf(nas), pending = listOf(action), sessions = listOf(session("budi")),
            controlFailure = RuntimeException("BRAS bisu"),
        )

        fx.runner.execute(tenantId, now)

        assertThat(action.status).isEqualTo(BngActionStatus.PENDING)
        assertThat(action.detail).contains("BRAS bisu")
    }

    @Test
    fun `kegagalan DAE melewati batas usia ditandai FAILED (menyerah)`() {
        val nas = nas(NasReachability.DIRECT)
        val old = now.minusSeconds(2 * 3600) // > maxRetry 1 jam
        val action = disconnect(nas.id, "budi", at = old)
        val fx = fixture(
            nas = listOf(nas), pending = listOf(action), sessions = listOf(session("budi")),
            controlFailure = RuntimeException("BRAS bisu"),
        )

        fx.runner.execute(tenantId, now)

        assertThat(action.status).isEqualTo(BngActionStatus.FAILED)
        assertThat(action.detail).contains("BRAS bisu")
    }

    // ---- Fixture & fake ----

    private class Fixture(
        val runner: RadiusSessionControlRunner,
        val actions: FakeScActionRepo,
        val control: FakeSessionControl,
        val radacct: FakeReadPort,
    )

    private fun fixture(
        nas: List<Nas>,
        pending: List<BngAction>,
        sessions: List<SessionObservation>,
        controlFailure: Exception? = null,
        slug: String? = this.slug,
    ): Fixture {
        val actions = FakeScActionRepo(pending)
        val control = FakeSessionControl(controlFailure)
        val radacct = FakeReadPort(sessions)
        val runner = RadiusSessionControlRunner(
            FakeScTenantApi(tenantId, slug),
            FakeNasRepo(nas),
            actions,
            radacct,
            control,
            RadiusProperties(),
        )
        return Fixture(runner, actions, control, radacct)
    }

    private fun nas(reachability: NasReachability) = Nas.create(
        tenantId = tenantId,
        name = "BRAS-$reachability",
        vendor = NasVendor.MIKROTIK,
        address = "203.0.113.9",
        nasIdentifier = null,
        coaSecret = "s3cr3t",
        collectorId = null,
        reachability = reachability,
    )

    private fun disconnect(nasId: UUID, username: String, at: Instant = now) = BngAction.disconnect(
        tenantId, subscriberAccessId = UuidV7.generate(), nasId = nasId, username = username,
        requestedBy = null, requestedByEmail = null, at = at,
    )

    private fun coa(nasId: UUID, username: String, down: Int, up: Int, at: Instant = now) = BngAction.coa(
        tenantId, subscriberAccessId = UuidV7.generate(), nasId = nasId, username = username,
        downMbps = down, upMbps = up, requestedBy = null, requestedByEmail = null, at = at,
    )

    private fun session(username: String) = SessionObservation(
        username = username,
        online = true,
        nasIp = "10.20.0.1",
        framedIp = "100.64.0.1",
        sessionId = "s-$username",
        callingStationId = null,
        uptimeSeconds = 3600,
        inOctets = 0,
        outOctets = 0,
    )
}

private class FakeScTenantApi(private val id: UUID, private val slug: String?) : TenantApi {
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

private class FakeNasRepo(private val all: List<Nas>) : NasRepository {
    override fun findAll(): List<Nas> = all
    override fun findById(id: UUID): Nas? = all.firstOrNull { it.id == id }
    override fun save(nas: Nas): Nas = notUsed()
    override fun existsByName(name: String): Boolean = notUsed()
    override fun deleteById(id: UUID): Unit = notUsed()
    private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
}

private class FakeScActionRepo(private val pending: List<BngAction>) : BngActionRepository {
    val saved = mutableListOf<BngAction>()
    var sessionControlNasIds: Collection<UUID>? = null

    override fun save(action: BngAction): BngAction {
        saved += action
        return action
    }

    override fun findById(id: UUID): BngAction? = pending.firstOrNull { it.id == id }
    override fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction> = emptyList()
    override fun findServerProvisioningPending(limit: Int): List<BngAction> = emptyList()
    override fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int): List<BngAction> {
        sessionControlNasIds = nasIds
        return pending.filter { it.nasId in nasIds }.take(limit)
    }
}

private class FakeReadPort(private val sessions: List<SessionObservation>) : RadiusAccountingReadPort {
    val calls = mutableListOf<Pair<UUID, String>>()
    override fun isConfigured(): Boolean = true
    override fun activeSessions(tenantId: UUID, tenantCode: String): List<SessionObservation> {
        calls += tenantId to tenantCode
        return sessions
    }
}

private class FakeSessionControl(private val failWith: Exception?) : RadiusSessionControlPort {
    data class DisconnectCall(
        val host: String,
        val secret: String,
        val username: String,
        val acctSessionId: String?,
        val nasIp: String?,
        val identifier: Int,
    )

    data class CoaCall(
        val host: String,
        val secret: String,
        val username: String,
        val downMbps: Int,
        val upMbps: Int,
        val acctSessionId: String?,
        val identifier: Int,
    )

    val disconnects = mutableListOf<DisconnectCall>()
    val coas = mutableListOf<CoaCall>()

    override fun disconnect(
        host: String,
        secret: String,
        username: String,
        acctSessionId: String?,
        nasIp: String?,
        identifier: Int,
    ) {
        failWith?.let { throw it }
        disconnects += DisconnectCall(host, secret, username, acctSessionId, nasIp, identifier)
    }

    override fun changeRate(
        host: String,
        secret: String,
        username: String,
        downMbps: Int,
        upMbps: Int,
        acctSessionId: String?,
        identifier: Int,
    ) {
        failWith?.let { throw it }
        coas += CoaCall(host, secret, username, downMbps, upMbps, acctSessionId, identifier)
    }
}
