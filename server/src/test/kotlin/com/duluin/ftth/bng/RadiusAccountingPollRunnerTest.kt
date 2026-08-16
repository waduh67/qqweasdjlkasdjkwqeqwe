package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.BngSessionIngestService
import com.duluin.ftth.bng.application.service.RadiusAccountingPollRunner
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SessionObservation
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.bng.domain.model.TrafficSample
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Menguji pekerja poll jalur-BACA RADIUS server-side ([RadiusAccountingPollRunner]) dengan
 * [BngSessionIngestService] NYATA di atas fake repo (tanpa Spring/DB/radius-db). Inti yang
 * diuji: kode tenant (`slug`) diresolusi lalu diteruskan ke pembaca `radacct` sebagai penyaring;
 * observasi (username sudah bare dari adapter) diserap → `radius_session` di-upsert & titik
 * `accounting_record` dicatat dengan `nasId = null` (baca global tak memetakan UUID NAS);
 * username tak dikenal dilewati; tenant tanpa slug tak menyentuh pembaca. Jam tetap lewat
 * [RadiusAccountingPollRunner.execute].
 */
class RadiusAccountingPollRunnerTest {

    private val tenantId = UuidV7.generate()
    private val slug = "acme"
    private val now: Instant = Instant.parse("2026-07-30T00:00:00Z")

    @Test
    fun `meresolusi slug, menyaring pembaca, dan menyerap sesi ke radius_session + accounting`() {
        val budi = access("budi")
        val fixture = fixture(
            accesses = listOf(budi),
            observations = listOf(
                observation("budi", nasIp = "10.0.0.1", up = 111, down = 222),
                // Username tak dikenal → dilewati (bukan dibuatkan akun).
                observation("hantu", nasIp = "10.0.0.1", up = 9, down = 9),
            ),
        )

        fixture.runner.execute(tenantId, now)

        // Pembaca disaring dengan (tenantId, slug).
        assertThat(fixture.read.calls).containsExactly(tenantId to slug)

        // Sesi budi ter-upsert: nasId null, nasIp jejak, online, username = akun (bare).
        val session = fixture.sessions.saved.single()
        assertThat(session.subscriberAccessId).isEqualTo(budi.id)
        assertThat(session.username).isEqualTo("budi")
        assertThat(session.online).isTrue()
        assertThat(session.nasId).isNull()
        assertThat(session.nasIp).isEqualTo("10.0.0.1")
        assertThat(session.lastSeenAt).isEqualTo(now)

        // Satu titik akunting untuk budi saja (hantu tak cocok akun).
        val point = fixture.accounting.saved.single()
        assertThat(point.subscriberAccessId).isEqualTo(budi.id)
        assertThat(point.nasId).isNull()
        assertThat(point.inOctets).isEqualTo(111)
        assertThat(point.outOctets).isEqualTo(222)
        assertThat(point.time).isEqualTo(now)
    }

    @Test
    fun `titik akunting dicap waktu NAS, bukan waktu poll`() {
        val budi = access("budi")
        // NAS terakhir memperbarui penghitung 4 menit lalu (Interim-Update jarang, mis. 5 menit),
        // sedangkan poller membacanya SEKARANG. Memakai waktu poll akan memampatkan pertambahan
        // 5 menit ke jarak 30 detik → laju Mbps melar berlipat, diselingi titik berlaju nol saat
        // interim belum bergerak. Waktu NAS membuat cuplikan kembar runtuh jadi satu baris.
        val interimAt = now.minusSeconds(240)
        val fixture = fixture(
            accesses = listOf(budi),
            observations = listOf(observation("budi", nasIp = "10.0.0.1", up = 111, down = 222, countersAt = interimAt)),
        )

        fixture.runner.execute(tenantId, now)

        assertThat(fixture.accounting.saved.single().time).isEqualTo(interimAt)
        // Keadaan sesi tetap dicap waktu baca: "kapan terakhir kita melihatnya hidup" memang
        // pertanyaan tentang jam kita, bukan jam NAS.
        assertThat(fixture.sessions.saved.single().lastSeenAt).isEqualTo(now)
    }

    @Test
    fun `tenant tanpa slug dilewati tanpa menyentuh pembaca radacct`() {
        val fixture = fixture(accesses = emptyList(), observations = emptyList(), slug = null)

        fixture.runner.execute(tenantId, now)

        assertThat(fixture.read.calls).isEmpty()
        assertThat(fixture.sessions.saved).isEmpty()
        assertThat(fixture.accounting.saved).isEmpty()
    }

    @Test
    fun `meneruskan username MAC akun aktif ke pembaca radacct`() {
        val fixture = fixture(
            accesses = listOf(access("budi")),
            observations = emptyList(),
            macUsernames = listOf("aa:bb:cc:dd:ee:ff"),
        )

        fixture.runner.execute(tenantId, now)

        // Daftar MAC akun aktif diteruskan sebagai penyaring tambahan (cabang `username = ANY(?)`)
        // agar sesi DHCP/Static yang ditulis polos tanpa prefiks ikut terbaca.
        assertThat(fixture.read.lastMacUsernames).containsExactly("aa:bb:cc:dd:ee:ff")
    }

    @Test
    fun `tanpa sesi hidup tak menyerap apa pun`() {
        val fixture = fixture(accesses = listOf(access("budi")), observations = emptyList())

        fixture.runner.execute(tenantId, now)

        // Pembaca tetap dipanggil (dengan slug), tapi tak ada penyerapan.
        assertThat(fixture.read.calls).containsExactly(tenantId to slug)
        assertThat(fixture.sessions.saved).isEmpty()
        assertThat(fixture.accounting.saved).isEmpty()
    }

    // Inti bug lapangan: kabel dicabut, sesi hilang dari radacct — dan dulu tak ada satu pun
    // yang menulis online=false, jadi peta memajang "Online" berjam-jam sesudahnya.
    @Test
    fun `sesi yang lenyap dari radacct ditandai putus`() {
        val budi = access("budi")
        val sejakTadi = now.minusSeconds(3600)
        val fixture = fixture(
            accesses = listOf(budi),
            observations = emptyList(),
            existingSessions = listOf(onlineSession(budi, observedAt = sejakTadi)),
        )

        fixture.runner.execute(tenantId, now)

        val putus = fixture.sessions.saved.single()
        assertThat(putus.online).isFalse()
        // Milik sesi yang berjalan ikut dikosongkan — IP sesi yang sudah tutup bukan lagi
        // alamat pelanggan.
        assertThat(putus.framedIp).isNull()
        assertThat(putus.uptimeSeconds).isNull()
        assertThat(putus.startedAt).isNull()
        // lastSeenAt TIDAK digeser: itu bahan "putus sejak kapan" di layar.
        assertThat(putus.lastSeenAt).isEqualTo(sejakTadi)
        // Sesi mati tak punya trafik untuk dicatat.
        assertThat(fixture.accounting.saved).isEmpty()
    }

    @Test
    fun `sesi yang masih hidup tak ikut tersapu`() {
        val budi = access("budi")
        val siti = access("siti")
        val fixture = fixture(
            accesses = listOf(budi, siti),
            observations = listOf(observation("budi", nasIp = "10.0.0.1", up = 1, down = 2)),
            existingSessions = listOf(
                onlineSession(budi, observedAt = now.minusSeconds(3600)),
                onlineSession(siti, observedAt = now.minusSeconds(3600)),
            ),
        )

        fixture.runner.execute(tenantId, now)

        val perAkun = fixture.sessions.saved.associateBy { it.subscriberAccessId }
        assertThat(perAkun[budi.id]?.online).isTrue()
        assertThat(perAkun[siti.id]?.online).isFalse()
    }

    // ---- Fixture & fake ----

    private class Fixture(
        val runner: RadiusAccountingPollRunner,
        val read: FakeReadPort,
        val sessions: FakeSessionRepo,
        val accounting: FakeAccountingRepo,
    )

    private fun fixture(
        accesses: List<SubscriberAccess>,
        observations: List<SessionObservation>,
        slug: String? = this.slug,
        macUsernames: List<String> = emptyList(),
        existingSessions: List<RadiusSession> = emptyList(),
    ): Fixture {
        val read = FakeReadPort(observations)
        val sessions = FakeSessionRepo(existingSessions)
        val accounting = FakeAccountingRepo()
        // Satu repo dipakai bersama: ingest me-resolusi akun per username, runner menariknya
        // untuk daftar MAC akun aktif yang diteruskan ke pembaca.
        val accessRepo = FakeAccessRepo(accesses, macUsernames)
        val ingest = BngSessionIngestService(accessRepo, sessions, accounting)
        val runner = RadiusAccountingPollRunner(FakeTenantApi(tenantId, slug), read, accessRepo, ingest)
        return Fixture(runner, read, sessions, accounting)
    }

    private fun access(username: String) = SubscriberAccess.create(
        tenantId = tenantId,
        subscriptionId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        username = username,
        secret = "rahasia123",
        planId = UuidV7.generate(),
        nasId = UuidV7.generate(),
        status = AccessStatus.ACTIVE,
    )

    /** Sesi yang sudah tercatat online sejak [observedAt] — keadaan sebelum putaran poll. */
    private fun onlineSession(access: SubscriberAccess, observedAt: Instant) = RadiusSession.start(
        tenantId = tenantId,
        subscriberAccessId = access.id,
        subscriptionId = access.subscriptionId,
        customerId = access.customerId,
        username = access.username,
        online = true,
        nasId = null,
        nasIp = "10.0.0.1",
        framedIp = "100.64.0.1",
        sessionId = "s-${access.username}",
        callingStationId = null,
        uptimeSeconds = 3600,
        observedAt = observedAt,
    )

    private fun observation(
        username: String,
        nasIp: String?,
        up: Long?,
        down: Long?,
        countersAt: Instant? = null,
    ) = SessionObservation(
        username = username,
        online = true,
        nasIp = nasIp,
        framedIp = "100.64.0.1",
        sessionId = "s-$username",
        callingStationId = null,
        uptimeSeconds = 3600,
        inOctets = up,
        outOctets = down,
        countersAt = countersAt,
    )

    private class FakeReadPort(private val observations: List<SessionObservation>) : RadiusAccountingReadPort {
        val calls = mutableListOf<Pair<UUID, String>>()
        var lastMacUsernames: List<String>? = null
        override fun isConfigured(): Boolean = true
        override fun activeSessions(
            tenantId: UUID,
            tenantCode: String,
            macUsernames: List<String>,
        ): List<SessionObservation> {
            calls += tenantId to tenantCode
            lastMacUsernames = macUsernames
            return observations
        }
    }

    /** [existing] = sesi yang sudah tersimpan sebelum putaran ini (bahan uji sapuan). */
    private class FakeSessionRepo(existing: List<RadiusSession> = emptyList()) : RadiusSessionRepository {
        val saved = mutableListOf<RadiusSession>()
        private val byAccess = existing.associateBy { it.subscriberAccessId }.toMutableMap()

        override fun save(session: RadiusSession): RadiusSession {
            saved += session
            byAccess[session.subscriberAccessId] = session
            return session
        }

        // Kosong = sesi baru → jalur RadiusSession.start; terisi = jalur observe/upsert.
        override fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSession? = byAccess[subscriberAccessId]
        override fun findBySubscriberAccessIds(subscriberAccessIds: Collection<UUID>): Map<UUID, RadiusSession> =
            emptyMap()

        override fun findAllForActiveAccounts(): List<RadiusSession> = byAccess.values.toList()

        override fun findOnline(): List<RadiusSession> = byAccess.values.filter { it.online }
    }

    private class FakeAccountingRepo : AccountingRecordRepository {
        val saved = mutableListOf<AccountingRecordPoint>()
        override fun saveAll(points: List<AccountingRecordPoint>) {
            saved += points
        }

        override fun trafficSince(subscriberAccessId: UUID, since: Instant, bucketSeconds: Long): List<TrafficSample> =
            throw UnsupportedOperationException("tak dipakai di uji ini")

        override fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long> =
            throw UnsupportedOperationException("tak dipakai di uji ini")
    }

    private class FakeAccessRepo(
        private val accesses: List<SubscriberAccess>,
        private val activeMacUsernames: List<String> = emptyList(),
    ) : SubscriberAccessRepository {
        override fun findByUsername(username: String): SubscriberAccess? = accesses.firstOrNull { it.username == username }
        override fun findActiveMacUsernames(): List<String> = activeMacUsernames
        override fun save(access: SubscriberAccess): SubscriberAccess = notUsed()
        override fun findById(id: UUID): SubscriberAccess? = notUsed()
        override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByCustomerIds(customerIds: Collection<UUID>): List<SubscriberAccess> =
            notUsed()

        override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
        override fun findActiveOnNas(): List<SubscriberAccess> = notUsed()
        override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = notUsed()
        override fun countByNasId(nasId: UUID): Long = notUsed()
        override fun deleteById(id: UUID): Unit = notUsed()
        override fun findAll(): List<com.duluin.ftth.bng.domain.model.SubscriberAccess> = notUsed()
        private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
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
}
