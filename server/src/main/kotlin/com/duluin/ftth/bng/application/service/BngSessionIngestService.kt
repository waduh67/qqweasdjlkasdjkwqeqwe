package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.RadiusSessionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.integration.BngSessionsReported
import com.duluin.ftth.common.integration.ReportedRadiusSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Menyerap laporan sesi PPPoE dari collector ke dalam module bng: memutakhirkan sesi
 * terkini per akun dan mencatat akunting deret-waktu untuk tren trafik.
 *
 * Dipisah dari jalur pengguna karena digerakkan event (lihat [BngSessionListener]):
 * ia butuh batas transaksinya sendiri saat dipanggil pasca-commit. Sesi dengan
 * username yang tak cocok akun mana pun DILEWATI, bukan dibuatkan akun — pendaftaran
 * akun adalah jalur provisioning terpisah; ini murni observasi.
 *
 * REQUIRES_NEW: listener berjalan pada fase AFTER_COMMIT transaksi monitoring yang
 * sudah selesai — tanpa transaksi baru, tulisan di sini takkan pernah ter-commit.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class BngSessionIngestService(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val radiusSessionRepository: RadiusSessionRepository,
    private val accountingRecordRepository: AccountingRecordRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ingest(event: BngSessionsReported) {
        val points = mutableListOf<AccountingRecordPoint>()
        var matched = 0
        for (reported in event.sessions) {
            val access = subscriberAccessRepository.findByUsername(reported.username) ?: continue
            matched++
            upsertSession(access, event, reported)
            // Hanya sesi online yang membawa penghitung akunting; yang offline tak
            // punya trafik untuk direkam, cukup keadaannya yang dimutakhirkan.
            if (reported.online) {
                points += AccountingRecordPoint(
                    time = event.collectedAt,
                    tenantId = event.tenantId,
                    subscriberAccessId = access.id,
                    nasId = event.nasId,
                    inOctets = reported.inOctets,
                    outOctets = reported.outOctets,
                    uptimeSeconds = reported.uptimeSeconds,
                )
            }
        }
        accountingRecordRepository.saveAll(points)
        log.debug(
            "Ingest sesi BRAS {} batch {}: {}/{} sesi cocok akun",
            event.nasId, event.batchId, matched, event.sessions.size,
        )
    }

    private fun upsertSession(access: SubscriberAccess, event: BngSessionsReported, reported: ReportedRadiusSession) {
        val session = radiusSessionRepository.findBySubscriberAccessId(access.id)?.apply {
            observe(
                online = reported.online,
                nasId = event.nasId,
                nasIp = reported.nasIp,
                framedIp = reported.framedIp,
                sessionId = reported.sessionId,
                callingStationId = reported.callingStationId,
                uptimeSeconds = reported.uptimeSeconds,
                observedAt = event.collectedAt,
            )
        } ?: RadiusSession.start(
            tenantId = event.tenantId,
            subscriberAccessId = access.id,
            subscriptionId = access.subscriptionId,
            customerId = access.customerId,
            username = access.username,
            online = reported.online,
            nasId = event.nasId,
            nasIp = reported.nasIp,
            framedIp = reported.framedIp,
            sessionId = reported.sessionId,
            callingStationId = reported.callingStationId,
            uptimeSeconds = reported.uptimeSeconds,
            observedAt = event.collectedAt,
        )
        radiusSessionRepository.save(session)
    }
}
