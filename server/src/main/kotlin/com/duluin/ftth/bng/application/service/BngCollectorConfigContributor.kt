package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.common.integration.BngActionDispatch
import com.duluin.ftth.common.integration.CollectorConfigContributor
import com.duluin.ftth.common.integration.NasPollTarget
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Menyumbang BRAS milik tenant ke konfigurasi polling collector, lewat seam shared
 * kernel [CollectorConfigContributor] sehingga monitoring tak perlu mengimpor bng.
 *
 * Sebuah BRAS ikut di-polling collector ini bila ditugaskan padanya secara eksplisit
 * ([Nas.collectorId] == collector) ATAU belum ditugaskan ke collector mana pun
 * ([Nas.collectorId] == null) — bawaan yang benar untuk ISP satu collector, cermin
 * kebijakan "penugasan kosong = semua OLT" di monitoring. BRAS nonaktif dilewati.
 *
 * [NasPollTarget.expectedUsernames] diisi akun PPPoE berstatus aktif pada BRAS itu;
 * hanya adapter simulator yang memakainya (memerankan sesi yang cocok pelanggan
 * nyata), adapter sungguhan mengabaikannya.
 */
@Component
class BngCollectorConfigContributor(
    private val nasRepository: NasRepository,
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val bngActions: BngActionService,
) : CollectorConfigContributor {

    override fun nasTargetsFor(collectorId: UUID, tenantId: UUID): List<NasPollTarget> =
        nasForCollector(collectorId).map { nas -> nas.toPollTarget() }

    /**
     * Perintah BRAS yang menunggu untuk BRAS yang dijangkau collector ini. Ditandai
     * DISPATCHED saat diserahkan (di dalam transaksi denyut), lalu dikirim ulang tiap
     * denyut sampai di-ACK — eksekusi harus idempoten (at-least-once).
     */
    override fun pendingBngActionsFor(collectorId: UUID, tenantId: UUID): List<BngActionDispatch> {
        val nasIds = nasForCollector(collectorId).map { it.id }
        if (nasIds.isEmpty()) return emptyList()
        return bngActions.claimDispatch(nasIds)
    }

    /**
     * BRAS yang di-polling collector ini: ditugaskan padanya secara eksplisit
     * ([Nas.collectorId] == collector) ATAU belum ditugaskan ke mana pun (null) —
     * bawaan benar untuk ISP satu collector. BRAS nonaktif dilewati.
     */
    private fun nasForCollector(collectorId: UUID): List<Nas> =
        nasRepository.findAll()
            .filter { it.enabled && (it.collectorId == collectorId || it.collectorId == null) }

    private fun Nas.toPollTarget(): NasPollTarget {
        val activeUsernames = subscriberAccessRepository.findByNasId(id)
            .filter { it.status == AccessStatus.ACTIVE }
            .map { it.username }
        return NasPollTarget(
            nasId = id,
            name = name,
            vendor = vendor.name,
            host = address,
            adapterType = vendor.name,
            expectedUsernames = activeUsernames,
        )
    }
}
