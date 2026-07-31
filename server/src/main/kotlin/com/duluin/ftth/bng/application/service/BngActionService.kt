package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.RadiusGroups
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.catalog.PlanNetworkRef
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
    private val subscriberAccessRepository: SubscriberAccessRepository,
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
     * Antre PROVISION: tulis kredensial + keanggotaan grup paket akun ke RADIUS
     * ("RADIUS jadi pusat"). Grup diturunkan dari [SubscriberAccess.planId]; password
     * TIDAK dititip di sini — diresolusi saat [claimDispatch]. No-op bila akun belum di
     * BRAS. Mengembalikan true bila benar-benar mengantre.
     */
    fun enqueueProvision(access: SubscriberAccess, requestedBy: UUID?, requestedByEmail: String?): Boolean {
        val nasId = access.nasId ?: return skipNoNas(access, "PROVISION")
        saveProvision(access, nasId, RadiusGroups.normal(access.planId), requestedBy, requestedByEmail)
        return true
    }

    /**
     * Antre DEPROVISION: cabut seluruh otorisasi akun (per username) dari BRAS yang kini
     * menaunginya. Dipakai saat hapus akun/terminasi. No-op bila akun belum di BRAS.
     */
    fun enqueueDeprovision(access: SubscriberAccess, requestedBy: UUID?, requestedByEmail: String?): Boolean {
        val nasId = access.nasId ?: return skipNoNas(access, "DEPROVISION")
        enqueueDeprovisionAt(nasId, access.tenantId, access.username, access.authType, requestedBy, requestedByEmail)
        return true
    }

    /**
     * Antre DEPROVISION pada BRAS TERTENTU (bukan yang kini di akun) — dipakai saat akun
     * dipindah BRAS: otorisasi di BRAS lama dicabut agar tak menggantung. Per-username,
     * tanpa menaut akun, jadi selamat dari CASCADE bila akunnya kelak dihapus. [authType]
     * dibawa agar jalur-tulis tahu memetakan identitas (slug-prefix vs MAC) tanpa akun.
     */
    @Suppress("LongParameterList")
    fun enqueueDeprovisionAt(
        nasId: UUID,
        tenantId: UUID,
        username: String,
        authType: AuthType,
        requestedBy: UUID?,
        requestedByEmail: String?,
    ) {
        bngActionRepository.save(
            BngAction.deprovision(
                tenantId = tenantId,
                nasId = nasId,
                username = username,
                authType = authType,
                requestedBy = requestedBy,
                requestedByEmail = requestedByEmail,
            ),
        )
    }

    /**
     * Antre SYNC_GROUP: setel atribut grup paket ([plan]) di sebuah BRAS — rate-limit
     * normal, batas sesi ([PlanNetworkRef.connectionLimit]), dan grup throttle FUP bila
     * paket ber-FUP. Tingkat-grup (bukan per-akun): satu baris mengubah kecepatan semua
     * akun di paket itu. Nilai jaringan dibaca live dari katalog oleh pemanggil.
     */
    @Suppress("LongParameterList")
    fun enqueueSyncGroup(
        nasId: UUID,
        tenantId: UUID,
        plan: PlanNetworkRef,
        requestedBy: UUID?,
        requestedByEmail: String?,
    ) {
        val fupGroupname = if (plan.fupEnabled && plan.fupRateLimit != null) RadiusGroups.fup(plan.planId) else null
        bngActionRepository.save(
            BngAction.syncGroup(
                tenantId = tenantId,
                nasId = nasId,
                groupname = RadiusGroups.normal(plan.planId),
                rateLimit = plan.rateLimit,
                simultaneousUse = plan.connectionLimit,
                fupGroupname = fupGroupname,
                fupRateLimit = plan.fupRateLimit,
                requestedBy = requestedBy,
                requestedByEmail = requestedByEmail,
            ),
        )
    }

    /**
     * Antre penerapan throttle FUP: pindah keanggotaan grup akun ke grup FUP paket (lewat
     * PROVISION ulang — penulis RADIUS menghapus keanggotaan lama lalu menulis grup FUP,
     * jadi swap-nya atomik) lalu CoA ke kecepatan FUP agar sesi hidup langsung melambat.
     * Dipicu sistem (pelaku null). No-op bila akun belum di BRAS atau paket tak menyediakan
     * kecepatan FUP. Mengembalikan true bila benar-benar mengantre.
     */
    fun enqueueApplyFup(access: SubscriberAccess, plan: PlanNetworkRef): Boolean {
        val nasId = access.nasId ?: return skipNoNas(access, "APPLY_FUP")
        val fupDown = plan.fupDownMbps
        val fupUp = plan.fupUpMbps
        if (!plan.fupEnabled || fupDown == null || fupUp == null) return false
        saveProvision(access, nasId, RadiusGroups.fup(plan.planId), requestedBy = null, requestedByEmail = null)
        enqueueCoa(access, fupDown, fupUp, requestedBy = null, requestedByEmail = null)
        return true
    }

    /**
     * Antre pencabutan throttle FUP: kembalikan keanggotaan akun ke grup normal paket
     * (PROVISION ulang) lalu CoA ke kecepatan penuh. Dipicu sistem saat pemakaian turun
     * atau siklus berganti. No-op bila akun belum di BRAS.
     */
    fun enqueueClearFup(access: SubscriberAccess, plan: PlanNetworkRef): Boolean {
        val nasId = access.nasId ?: return skipNoNas(access, "CLEAR_FUP")
        saveProvision(access, nasId, RadiusGroups.normal(access.planId), requestedBy = null, requestedByEmail = null)
        enqueueCoa(access, plan.downMbps, plan.upMbps, requestedBy = null, requestedByEmail = null)
        return true
    }

    /** Simpan satu PROVISION akun ke [groupname] tertentu (grup normal atau throttle FUP). */
    private fun saveProvision(
        access: SubscriberAccess,
        nasId: UUID,
        groupname: String,
        requestedBy: UUID?,
        requestedByEmail: String?,
    ) {
        bngActionRepository.save(
            BngAction.provision(
                tenantId = access.tenantId,
                subscriberAccessId = access.id,
                nasId = nasId,
                username = access.username,
                groupname = groupname,
                authType = access.authType,
                requestedBy = requestedBy,
                requestedByEmail = requestedByEmail,
            ),
        )
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

    private fun BngAction.toDispatch(): BngActionDispatch {
        // Password hanya untuk PROVISION: diresolusi+dekripsi dari akun saat klaim (repo
        // mengembalikan secret terdekripsi), TAK PERNAH disimpan di bng_action. Diangkut
        // ke collector lewat kanal TLS — tak ada cleartext at-rest baru.
        val password = if (action == BngActionType.PROVISION) {
            subscriberAccessId?.let { subscriberAccessRepository.findById(it)?.secret }
        } else {
            null
        }
        return BngActionDispatch(
            actionId = id,
            nasId = nasId,
            kind = action.name,
            username = username,
            downMbps = downMbps,
            upMbps = upMbps,
            groupname = groupname,
            password = password,
            rateLimit = rateLimit,
            simultaneousUse = simultaneousUse,
            fupGroupname = fupGroupname,
            fupRateLimit = fupRateLimit,
        )
    }
}
