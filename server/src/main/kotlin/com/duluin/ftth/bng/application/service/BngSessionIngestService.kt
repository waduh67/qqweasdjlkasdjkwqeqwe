package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SessionObservation
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.integration.BngSessionsReported
import com.duluin.ftth.common.integration.ReportedRadiusSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Menyerap observasi sesi PPPoE ke dalam module bng: memutakhirkan sesi terkini per akun
 * dan mencatat akunting deret-waktu untuk tren trafik. Dua sumber bermuara di sini:
 *  - **server-side** ([RadiusAccountingPoller]) yang membaca `radacct` langsung dari
 *    radius-db platform (RADIUS-as-a-service) — jalur aktif; dan
 *  - **collector** (event [BngSessionsReported]) — jalur lama, dipetakan ke observasi netral.
 *
 * Keduanya memakai inti serap yang sama ([ingest] neutral). Sesi dengan username yang tak
 * cocok akun mana pun DILEWATI, bukan dibuatkan akun — pendaftaran akun adalah jalur
 * provisioning terpisah; ini murni observasi.
 *
 * REQUIRES_NEW: pemanggil berjalan pasca-commit (listener collector) atau di luar transaksi
 * (poller terjadwal) — tanpa transaksi baru, tulisan di sini takkan pernah ter-commit.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class BngSessionIngestService(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val radiusSessionRepository: RadiusSessionRepository,
    private val accountingRecordRepository: AccountingRecordRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Jalur collector (lama): netralkan event lalu serap. [BngSessionsReported.nasId] jadi asal NAS. */
    fun ingest(event: BngSessionsReported) =
        ingest(event.tenantId, event.collectedAt, event.nasId, event.sessions.map { it.toObservation() })

    /**
     * Inti serap netral. [nasId] = NAS asal bila diketahui (jalur collector) atau null bila
     * tak terpetakan (jalur server membaca `radacct` global — hanya `nasipaddress` string yang
     * ada, bukan UUID NAS kita); [SessionObservation.nasIp] tetap terekam sebagai jejaknya.
     */
    fun ingest(tenantId: UUID, collectedAt: Instant, nasId: UUID?, observations: List<SessionObservation>) {
        val points = mutableListOf<AccountingRecordPoint>()
        var matched = 0
        for (observed in observations) {
            val access = subscriberAccessRepository.findByUsername(observed.username) ?: continue
            matched++
            upsertSession(access, tenantId, collectedAt, nasId, observed)
            // Hanya sesi online yang membawa penghitung akunting; yang offline tak
            // punya trafik untuk direkam, cukup keadaannya yang dimutakhirkan.
            if (observed.online) {
                points += AccountingRecordPoint(
                    time = collectedAt,
                    tenantId = tenantId,
                    subscriberAccessId = access.id,
                    nasId = nasId,
                    inOctets = observed.inOctets,
                    outOctets = observed.outOctets,
                    uptimeSeconds = observed.uptimeSeconds,
                )
            }
        }
        accountingRecordRepository.saveAll(points)
        log.debug("Ingest sesi BRAS (nas {}): {}/{} sesi cocok akun", nasId, matched, observations.size)
    }

    private fun upsertSession(
        access: SubscriberAccess,
        tenantId: UUID,
        collectedAt: Instant,
        nasId: UUID?,
        observed: SessionObservation,
    ) {
        val session = radiusSessionRepository.findBySubscriberAccessId(access.id)?.apply {
            observe(
                online = observed.online,
                nasId = nasId,
                nasIp = observed.nasIp,
                framedIp = observed.framedIp,
                sessionId = observed.sessionId,
                callingStationId = observed.callingStationId,
                uptimeSeconds = observed.uptimeSeconds,
                observedAt = collectedAt,
            )
        } ?: RadiusSession.start(
            tenantId = tenantId,
            subscriberAccessId = access.id,
            subscriptionId = access.subscriptionId,
            customerId = access.customerId,
            username = access.username,
            online = observed.online,
            nasId = nasId,
            nasIp = observed.nasIp,
            framedIp = observed.framedIp,
            sessionId = observed.sessionId,
            callingStationId = observed.callingStationId,
            uptimeSeconds = observed.uptimeSeconds,
            observedAt = collectedAt,
        )
        radiusSessionRepository.save(session)
    }

    private fun ReportedRadiusSession.toObservation() = SessionObservation(
        username = username,
        online = online,
        nasIp = nasIp,
        framedIp = framedIp,
        sessionId = sessionId,
        callingStationId = callingStationId,
        uptimeSeconds = uptimeSeconds,
        inOctets = inOctets,
        outOctets = outOctets,
    )
}
