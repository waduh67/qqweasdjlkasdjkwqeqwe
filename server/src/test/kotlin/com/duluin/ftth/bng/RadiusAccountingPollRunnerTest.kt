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
    fun `tenant tanpa slug dilewati tanpa menyentuh pembaca radacct`() {
        val fixture = fixture(accesses = emptyList(), observations = emptyList(), slug = null)

        fixture.runner.execute(tenantId, now)

        assertThat(fixture.read.calls).isEmpty()
        assertThat(fixture.sessions.saved).isEmpty()
        assertThat(fixture.accounting.saved).isEmpty()
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
    ): Fixture {
        val read = FakeReadPort(observations)
        val sessions = FakeSessionRepo()
        val accounting = FakeAccountingRepo()
        val ingest = BngSessionIngestService(FakeAccessRepo(accesses), sessions, accounting)
        val runner = RadiusAccountingPollRunner(FakeTenantApi(tenantId, slug), read, ingest)
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

    private fun observation(username: String, nasIp: String?, up: Long?, down: Long?) = SessionObservation(
        username = username,
        online = true,
        nasIp = nasIp,
        framedIp = "100.64.0.1",
        sessionId = "s-$username",
        callingStationId = null,
        uptimeSeconds = 3600,
        inOctets = up,
        outOctets = down,
    )

    private class FakeReadPort(private val observations: List<SessionObservation>) : RadiusAccountingReadPort {
        val calls = mutableListOf<Pair<UUID, String>>()
        override fun isConfigured(): Boolean = true
        override fun activeSessions(tenantId: UUID, tenantCode: String): List<SessionObservation> {
            calls += tenantId to tenantCode
            return observations
        }
    }

    private class FakeSessionRepo : RadiusSessionRepository {
        val saved = mutableListOf<RadiusSession>()
        override fun save(session: RadiusSession): RadiusSession {
            saved += session
            return session
        }

        // Selalu sesi baru (tak ada yang tersimpan sebelumnya) → jalur RadiusSession.start.
        override fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSession? = null
    }

    private class FakeAccountingRepo : AccountingRecordRepository {
        val saved = mutableListOf<AccountingRecordPoint>()
        override fun saveAll(points: List<AccountingRecordPoint>) {
            saved += points
        }

        override fun trafficSince(subscriberAccessId: UUID, since: Instant): List<TrafficSample> =
            throw UnsupportedOperationException("tak dipakai di uji ini")

        override fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long> =
            throw UnsupportedOperationException("tak dipakai di uji ini")
    }

    private class FakeAccessRepo(private val accesses: List<SubscriberAccess>) : SubscriberAccessRepository {
        override fun findByUsername(username: String): SubscriberAccess? = accesses.firstOrNull { it.username == username }
        override fun save(access: SubscriberAccess): SubscriberAccess = notUsed()
        override fun findById(id: UUID): SubscriberAccess? = notUsed()
        override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = notUsed()
        override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
        override fun findActiveOnNas(): List<SubscriberAccess> = notUsed()
        override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = notUsed()
        override fun countByNasId(nasId: UUID): Long = notUsed()
        override fun deleteById(id: UUID): Unit = notUsed()
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
