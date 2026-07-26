package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.integration.AcknowledgedBngAction
import com.duluin.ftth.common.integration.BngActionDispatch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Antrean perintah BRAS (jalur turun, Phase 7c): mengubah maksud operator (isolir,
 * Reset Login, ganti kecepatan) menjadi baris `bng_action`, menyerahkannya ke collector
 * lewat seam contributor, lalu menuntaskannya dari ACK.
 *
 * Terpisah dari [SubscriberAccessService] karena tiga pemanggilnya berbeda batas
 * transaksi: penambah antrean ikut transaksi pengguna (REQUIRED), [claimDispatch]
 * ikut transaksi denyut collector (REQUIRED), dan [acknowledge] butuh transaksinya
 * sendiri (REQUIRES_NEW) karena dijalankan listener pasca-commit.
 */
@Service
@Transactional
class BngActionService(
    private val bngActionRepository: BngActionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Antre DISCONNECT untuk memutus sesi akun (dipakai isolir & Reset Login).
     * No-op bila akun belum ditugaskan ke BRAS mana pun — tak ada tempat mengirim
     * perintah. Mengembalikan true bila benar-benar mengantre.
     */
    fun enqueueDisconnect(access: SubscriberAccess, requestedBy: UUID?, requestedByEmail: String?): Boolean {
        val nasId = access.nasId ?: return skipNoNas(access, "DISCONNECT")
        bngActionRepository.save(
            BngAction.disconnect(
                tenantId = access.tenantId,
                subscriberAccessId = access.id,
                nasId = nasId,
                username = access.username,
                requestedBy = requestedBy,
                requestedByEmail = requestedByEmail,
            ),
        )
        return true
    }

    /**
     * Antre CoA untuk mengganti kecepatan sesi hidup tanpa memutusnya. No-op bila akun
     * belum di BRAS. Mengembalikan true bila benar-benar mengantre.
     */
    @Suppress("LongParameterList")
    fun enqueueCoa(
        access: SubscriberAccess,
        downMbps: Int,
        upMbps: Int,
        requestedBy: UUID?,
        requestedByEmail: String?,
    ): Boolean {
        val nasId = access.nasId ?: return skipNoNas(access, "CoA")
        bngActionRepository.save(
            BngAction.coa(
                tenantId = access.tenantId,
                subscriberAccessId = access.id,
                nasId = nasId,
                username = access.username,
                downMbps = downMbps,
                upMbps = upMbps,
                requestedBy = requestedBy,
                requestedByEmail = requestedByEmail,
            ),
        )
        return true
    }

    /**
     * Klaim perintah belum-tuntas untuk sekumpulan BRAS, tandai DISPATCHED, kembalikan
     * sebagai dispatch netral (tipe shared-kernel) untuk collector. Dipanggil contributor
     * DI DALAM transaksi denyut (REQUIRED), jadi penandaan ikut ter-commit bersama denyut.
     */
    fun claimDispatch(nasIds: Collection<UUID>): List<BngActionDispatch> =
        bngActionRepository.findDispatchableByNasIds(nasIds).map { action ->
            action.markDispatched()
            bngActionRepository.save(action)
            action.toDispatch()
        }

    /**
     * Menuntaskan perintah dari ACK collector. REQUIRES_NEW: dipanggil listener pada
     * fase AFTER_COMMIT denyut yang sudah selesai — tanpa transaksi baru, tulisan di
     * sini takkan ter-commit. ACK ganda (at-least-once) atas perintah yang sudah
     * terminal diabaikan diam-diam.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun acknowledge(results: List<AcknowledgedBngAction>) {
        for (result in results) {
            val action = bngActionRepository.findById(result.actionId) ?: continue
            if (action.isTerminal) continue
            if (result.success) action.complete() else action.fail(result.detail)
            bngActionRepository.save(action)
        }
    }

    private fun skipNoNas(access: SubscriberAccess, kind: String): Boolean {
        log.debug("Akun {} belum ditugaskan ke BRAS — {} dilewati", access.username, kind)
        return false
    }

    private fun BngAction.toDispatch() = BngActionDispatch(
        actionId = id,
        nasId = nasId,
        kind = action.name,
        username = username,
        downMbps = downMbps,
        upMbps = upMbps,
    )
}
