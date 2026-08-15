package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.inbound.BrasSessionView
import com.duluin.ftth.bng.application.port.inbound.TrafficHistoryView
import com.duluin.ftth.bng.application.port.inbound.TrafficPoint
import com.duluin.ftth.bng.application.port.inbound.ViewBngSessionUseCase
import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Jalur baca BNG untuk UI: keadaan sesi PPPoE terkini sebuah akun ("B-ras Check")
 * dan tren trafiknya. Murni baca — tak menyentuh BRAS.
 *
 * Akun yang belum pernah terpantau tetap menghasilkan view (offline, waktu null),
 * membedakan "sedang offline" dari "akun tak dikenal" (yang melempar not-found).
 */
@Service
@Transactional(readOnly = true)
class BngMonitoringQueryService(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val radiusSessionRepository: RadiusSessionRepository,
    private val accountingRecordRepository: AccountingRecordRepository,
    private val nasRepository: NasRepository,
    /**
     * Ambang basi sesi — kunci yang sama dengan [BngApiService] supaya "B-ras Check" dan
     * badge online di peta/daftar tak pernah saling bertentangan untuk pelanggan yang sama.
     */
    @Value("\${ftth.bng.session-stale-after:PT3M}") private val sessionStaleAfter: Duration,
) : ViewBngSessionUseCase {

    override fun session(subscriberAccessId: UUID): BrasSessionView {
        val access = requireAccess(subscriberAccessId)
        val session = radiusSessionRepository.findBySubscriberAccessId(subscriberAccessId)
        return session?.toView(Instant.now()) ?: offlineView(access)
    }

    override fun traffic(subscriberAccessId: UUID, hours: Int): TrafficHistoryView {
        if (hours !in 1..MAX_HISTORY_HOURS) {
            throw ValidationException("Rentang riwayat harus 1-$MAX_HISTORY_HOURS jam")
        }
        requireAccess(subscriberAccessId)
        val since = Instant.now().minus(Duration.ofHours(hours.toLong()))
        val samples = accountingRecordRepository.trafficSince(subscriberAccessId, since, bucketSecondsFor(hours))
        val points = samples.map { TrafficPoint(it.time, it.downMbps, it.upMbps) }
        // Throughput "sekarang" = titik terakhir yang masih terhitung; ekor null (akun offline)
        // dilewati agar badge memantulkan laju nyata terakhir, bukan kekosongan.
        val current = samples.lastOrNull { it.downMbps != null || it.upMbps != null }
        val totalBytes = accountingRecordRepository
            .usageSince(listOf(subscriberAccessId), since)[subscriberAccessId] ?: 0L
        return TrafficHistoryView(
            subscriberAccessId = subscriberAccessId,
            hours = hours,
            points = points,
            currentDownMbps = current?.downMbps,
            currentUpMbps = current?.upMbps,
            totalBytes = totalBytes,
        )
    }

    /**
     * Lebar ember agar jumlah titik ≈ [TARGET_POINTS] apa pun rentangnya: rentang panjang
     * dibuat lebih kasar ketimbang membanjiri klien. Minimal [POLL_INTERVAL_SECONDS] — tak ada
     * gunanya lebih halus dari selang cuplikan yang tersedia.
     */
    private fun bucketSecondsFor(hours: Int): Long {
        val windowSeconds = hours.toLong() * SECONDS_PER_HOUR
        return maxOf(POLL_INTERVAL_SECONDS, windowSeconds / TARGET_POINTS)
    }

    private fun requireAccess(id: UUID): SubscriberAccess =
        subscriberAccessRepository.findById(id) ?: throw NotFoundException("Akun jaringan $id tidak ditemukan")

    /** Nama BRAS diresolusi per-panggilan (satu akun, satu lookup) — bukan jalur panas. */
    private fun nasNameOf(nasId: UUID?): String? = nasId?.let { nasRepository.findById(it)?.name }

    /**
     * Baris sesi → view pada [now]. Sesi yang tak segar lagi disajikan sebagai putus lengkap
     * dengan milik sesi berjalan yang dikosongkan — layar ini yang dipakai teknisi memutuskan
     * apakah gangguan ada di rumah pelanggan atau di jaringan, jadi "Online" dengan IP yang
     * sudah tak berlaku adalah kebohongan yang mahal. [lastSeenAt] tetap dipajang: dari sanalah
     * "terakhir terpantau" dibaca.
     */
    private fun RadiusSession.toView(now: Instant): BrasSessionView {
        val live = isLiveAt(now, sessionStaleAfter)
        return BrasSessionView(
            subscriberAccessId = subscriberAccessId,
            username = username,
            online = live,
            framedIp = framedIp?.takeIf { live },
            nasId = nasId,
            nasName = nasNameOf(nasId),
            nasIp = nasIp,
            callingStationId = callingStationId,
            uptimeSeconds = uptimeSeconds?.takeIf { live },
            startedAt = startedAt?.takeIf { live },
            lastSeenAt = lastSeenAt,
        )
    }

    private fun offlineView(access: SubscriberAccess) = BrasSessionView(
        subscriberAccessId = access.id,
        username = access.username,
        online = false,
        framedIp = null,
        nasId = access.nasId,
        nasName = nasNameOf(access.nasId),
        nasIp = null,
        callingStationId = null,
        uptimeSeconds = null,
        startedAt = null,
        lastSeenAt = null,
    )

    private companion object {
        /** Batas atas rentang; data akunting mentah memang hanya disimpan 90 hari. */
        const val MAX_HISTORY_HOURS = 24 * 90
        const val SECONDS_PER_HOUR = 3600L

        /** Sasaran jumlah titik grafik apa pun rentangnya — kompromi kehalusan vs bobot payload. */
        const val TARGET_POINTS = 720L

        /** Ember tak lebih halus dari selang poll `radacct` (`ftth.radius.session-poll-interval`). */
        const val POLL_INTERVAL_SECONDS = 30L
    }
}
