package com.duluin.ftth.bng

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.application.service.BngMonitoringQueryService
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.bng.domain.model.TrafficSample
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Menguji jalur baca BNG untuk UI di atas fake repo (tanpa Spring/DB):
 *  - tren trafik ([BngMonitoringQueryService.traffic]) — throughput "sekarang" mengambil titik
 *    TERAKHIR yang masih terhitung (melewati ekor null saat akun offline), total pemakaian
 *    diambil dari `usageSince`, dan rentang di luar batas ditolak; dan
 *  - keadaan sesi ([BngMonitoringQueryService.session]) — baris yang tak segar lagi disajikan
 *    sebagai putus, bukan "Online" abadi.
 *
 * Perhitungan Mbps & bucketing SQL diuji terpisah di IT.
 */
class BngMonitoringQueryServiceTest {

    private val accessId: UUID = UuidV7.generate()
    private val t0: Instant = Instant.parse("2026-07-30T00:00:00Z")

    private fun service(
        samples: List<TrafficSample> = emptyList(),
        usage: Map<UUID, Long> = emptyMap(),
        access: SubscriberAccess? = access(),
        session: RadiusSession? = null,
    ) = BngMonitoringQueryService(
        FakeAccessRepo(access),
        FakeSessionRepo(session),
        FakeAccountingRepo(samples, usage),
        FakeNasRepo(),
        STALE_AFTER,
    )

    @Test
    fun `melipat Mbps kini + total pemakaian ke view, melewati ekor null`() {
        val samples = listOf(
            TrafficSample(t0, downMbps = 5.0, upMbps = 1.0),
            TrafficSample(t0.plusSeconds(60), downMbps = 8.0, upMbps = 2.0),
            // Ekor null (akun baru saja offline) — tak boleh menutupi laju nyata terakhir.
            TrafficSample(t0.plusSeconds(120), downMbps = null, upMbps = null),
        )

        val view = service(samples = samples, usage = mapOf(accessId to 123_456_789L)).traffic(accessId, 24)

        assertThat(view.hours).isEqualTo(24)
        assertThat(view.points).hasSize(3)
        // "Sekarang" = titik terakhir yang non-null (bukan ekor null).
        assertThat(view.currentDownMbps).isEqualTo(8.0)
        assertThat(view.currentUpMbps).isEqualTo(2.0)
        assertThat(view.totalBytes).isEqualTo(123_456_789L)
    }

    @Test
    fun `tanpa cuplikan, current null dan total nol`() {
        val view = service(samples = emptyList(), usage = emptyMap()).traffic(accessId, 6)

        assertThat(view.points).isEmpty()
        assertThat(view.currentDownMbps).isNull()
        assertThat(view.currentUpMbps).isNull()
        assertThat(view.totalBytes).isEqualTo(0L)
    }

    @Test
    fun `menolak rentang di luar batas`() {
        assertThatThrownBy { service().traffic(accessId, 0) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { service().traffic(accessId, 100_000) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `akun tak dikenal melempar not-found`() {
        assertThatThrownBy { service(access = null).traffic(accessId, 24) }
            .isInstanceOf(NotFoundException::class.java)
    }

    // Inti bug lapangan: kabel dicabut, sesi lenyap dari radacct, dan bila poller ikut mati
    // tak ada yang menandainya putus — layar "B-ras Check" tak boleh ikut membeku "Online".
    @Test
    fun `sesi yang tak segar lagi disajikan putus tanpa sisa IP`() {
        val basi = session(lastSeenAt = Instant.now().minus(STALE_AFTER).minusSeconds(60))

        val view = service(session = basi).session(accessId)

        assertThat(view.online).isFalse()
        assertThat(view.framedIp).isNull()
        assertThat(view.uptimeSeconds).isNull()
        assertThat(view.startedAt).isNull()
        // "Terakhir terpantau" justru yang dicari teknisi — tetap dipajang.
        assertThat(view.lastSeenAt).isEqualTo(basi.lastSeenAt)
    }

    @Test
    fun `sesi yang masih segar tetap online lengkap`() {
        val segar = session(lastSeenAt = Instant.now().minusSeconds(30))

        val view = service(session = segar).session(accessId)

        assertThat(view.online).isTrue()
        assertThat(view.framedIp).isEqualTo("100.64.0.7")
        assertThat(view.uptimeSeconds).isEqualTo(3600)
    }

    private fun session(lastSeenAt: Instant) = RadiusSession.start(
        tenantId = UuidV7.generate(),
        subscriberAccessId = accessId,
        subscriptionId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        username = "budi",
        online = true,
        nasId = null,
        nasIp = "10.0.0.1",
        framedIp = "100.64.0.7",
        sessionId = "s-budi",
        callingStationId = null,
        uptimeSeconds = 3600,
        observedAt = lastSeenAt,
    )

    private fun access() = SubscriberAccess.create(
        tenantId = UuidV7.generate(),
        subscriptionId = UuidV7.generate(),
        customerId = UuidV7.generate(),
        username = "budi",
        secret = "rahasia123",
        planId = UuidV7.generate(),
        nasId = null,
        status = AccessStatus.ACTIVE,
    )

    // ---- Fake repo (hanya jalur yang dipakai traffic() yang berperilaku) ----

    private inner class FakeAccessRepo(private val access: SubscriberAccess?) : SubscriberAccessRepository {
        override fun findById(id: UUID): SubscriberAccess? = access?.takeIf { id == accessId }
        override fun save(access: SubscriberAccess): SubscriberAccess = notUsed()
        override fun findAll(): List<SubscriberAccess> = notUsed()
        override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByCustomerIds(customerIds: Collection<UUID>): List<SubscriberAccess> =
            notUsed()

        override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByUsername(username: String): SubscriberAccess? = notUsed()
        override fun findByNasId(nasId: UUID): List<SubscriberAccess> = notUsed()
        override fun findByPlanId(planId: UUID): List<SubscriberAccess> = notUsed()
        override fun findActiveOnNas(): List<SubscriberAccess> = notUsed()
        override fun findIsolatedOnNas(): List<SubscriberAccess> = notUsed()
        override fun findActiveMacUsernames(): List<String> = notUsed()
        override fun existsBySubscriptionId(subscriptionId: UUID): Boolean = notUsed()
        override fun countByNasId(nasId: UUID): Long = notUsed()
        override fun deleteById(id: UUID): Unit = notUsed()
    }

    private class FakeAccountingRepo(
        private val samples: List<TrafficSample>,
        private val usage: Map<UUID, Long>,
    ) : AccountingRecordRepository {
        override fun trafficSince(subscriberAccessId: UUID, since: Instant, bucketSeconds: Long): List<TrafficSample> =
            samples
        override fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long> = usage
        override fun saveAll(points: List<AccountingRecordPoint>): Unit = notUsed()
    }

    private class FakeSessionRepo(private val session: RadiusSession?) : RadiusSessionRepository {
        override fun save(session: RadiusSession): RadiusSession = notUsed()
        override fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSession? = session
        override fun findBySubscriberAccessIds(subscriberAccessIds: Collection<UUID>): Map<UUID, RadiusSession> =
            notUsed()

        override fun findAllForActiveAccounts(): List<RadiusSession> = notUsed()

        override fun findOnline(): List<RadiusSession> = notUsed()
    }

    private class FakeNasRepo : NasRepository {
        override fun save(nas: Nas): Nas = notUsed()
        override fun findById(id: UUID): Nas? = notUsed()
        override fun findAll(): List<Nas> = notUsed()
        override fun existsByName(name: String): Boolean = notUsed()
        override fun findByNameIgnoreCase(name: String): Nas? = notUsed()
        override fun deleteById(id: UUID): Unit = notUsed()
    }

    private companion object {
        /** Sama dengan bawaan `ftth.bng.session-stale-after`. */
        val STALE_AFTER: Duration = Duration.ofMinutes(3)
    }
}

private fun notUsed(): Nothing = throw UnsupportedOperationException("tak dipakai di uji ini")
