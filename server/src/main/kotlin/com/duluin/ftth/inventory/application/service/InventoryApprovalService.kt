package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.InventoryApprovalDecisionEvent
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalAuditRepository
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalRepository
import com.duluin.ftth.inventory.domain.model.*
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Persetujuan bertingkat untuk mutasi gudang yang berisiko (restock, penyesuaian, susut).
 *
 * Dulu permintaan, keputusan, dan efeknya disimpan di `linkedMapOf` dalam memori proses:
 * satu restart di tengah rantai approval menghapus jejak siapa sudah menyetujui apa —
 * padahal justru jejak itulah alasan fitur ini ada. Sekarang semuanya lewat
 * [InventoryApprovalRepository].
 *
 * Perubahan kedua, yang lebih penting: TIER DITENTUKAN SERVER. `request()` tidak lagi
 * menerima [InventoryApprovalPolicy] dari pemanggil — ia membacanya dari
 * [InventoryApprovalPolicyService]. Selama kebijakan datang dari body request, orang yang
 * mengajukan penghapusbukuan aset juga menuliskan sendiri siapa yang boleh menyetujuinya.
 */
@Service
class InventoryApprovalService(
    private val approvals: InventoryApprovalRepository,
    private val policyService: InventoryApprovalPolicyService,
    private val emergencyAudit: InventoryApprovalAuditRepository,
    /**
     * Ledger ikut disuntik supaya status terminal LANGSUNG mengeksekusi efek yang dijanjikan.
     * Tanpa ini, "disetujui" hanyalah label di tabel approval dan stoknya tidak pernah
     * bergerak — lihat [executeEffect].
     */
    private val ledger: InventoryMovementLedgerService,
    private val clock: Clock = Clock.systemUTC(),
    private val events: ApplicationEventPublisher? = null,
    private val currentUser: CurrentUserProvider? = null,
    private val auditor: AuditRecorder? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun registerDelegation(delegation: ApproverDelegation) = approvals.saveDelegation(delegation)

    @Transactional
    fun request(command: CreateInventoryApproval): InventoryApprovalRequest {
        // currentOrNull, BUKAN current(): pemanggil terjadwal/worker tidak punya sesi login dan
        // current() akan meledak dengan IllegalStateException di sana. Pemeriksaan aktor memang
        // hanya relevan kalau permintaan datang dari sesi manusia.
        currentUser?.currentOrNull()?.let {
            if (it.tenantId != command.tenantId || it.userId != command.requesterId) throw ValidationException("approval actor does not match server context")
            if (command.emergencyReason != null && !it.hasPermission("inventory.approval.emergency")) throw ValidationException("emergency approval permission is required")
        }
        val prior = approvals.findByOperation(command.tenantId, command.operationKey)
        if (prior != null) return replayOrConflict(prior, command.operationHash)

        // Matriks dibaca di sini, dari server. Perhatikan bahwa `command` tidak punya satu pun
        // field kebijakan — itu memang intinya: klien tidak bisa mengarang tier-nya sendiri.
        val matrix = policyService.effectiveFor(command.tenantId, command.type)
        val policy = matrix.toPolicy()
        if (command.emergencyReason != null && !policy.emergencyAllowed) throw ValidationException("emergency approval is not enabled")

        val now = Instant.now(clock)
        // Override darurat MELANGKAHI seluruh rantai tier — itulah arti "override". Karena
        // kontrol empat-mata benar-benar dipotong, jejaknya ditulis di bawah dalam transaksi
        // yang SAMA: kalau auditnya gagal, overridenya ikut gagal.
        val emergency = command.emergencyReason != null
        val request = InventoryApprovalRequest(
            UuidV7.generate(), command.tenantId, command.type, command.amount, command.requesterId,
            command.custodianId, command.movementId, policy,
            InventoryApprovalPolicyService.snapshotHash(matrix), command.operationKey,
            command.operationHash, command.emergencyReason, now, now.plus(policy.expiry),
            if (emergency) InventoryApprovalStatus.APPROVED else InventoryApprovalStatus.PENDING,
        )
        // Penjaga sebenarnya terhadap dua request bersamaan adalah UNIQUE
        // (tenant_id, operation_key); yang kalah menerima baris pemenang sebagai replay.
        val raced = approvals.appendIfAbsent(request)
        if (raced != null) return replayOrConflict(raced, command.operationHash)

        if (emergency) recordEmergency(request, matrix, now)
        if (request.status == InventoryApprovalStatus.APPROVED) emitEffect(request, now)
        return request
    }

    @Transactional
    fun decide(approvalId: UUID, command: DecideInventoryApproval): InventoryApprovalRequest {
        val current = approvals.findById(approvalId) ?: throw ValidationException("approval does not exist")
        if (current.tenantId != command.tenantId) throw ValidationException("approval belongs to another tenant")

        val duplicate = current.decisions.firstOrNull { it.approverId == command.approverId && it.operationKey == command.operationKey }
        if (duplicate != null) {
            if (duplicate.operationHash != command.operationHash) throw ConflictException("decision operation key was used with a different payload")
            return current
        }

        val now = Instant.now(clock)
        if (now >= current.expiresAt && current.status == InventoryApprovalStatus.PENDING) {
            // Penandaan EXPIRED WAJIB bertahan walau panggilan ini berakhir dengan lemparan:
            // kalau ikut ter-rollback, permintaan yang sudah lewat tenggat tetap tampil PENDING
            // dan approver berikutnya terus mencoba menyetujuinya selamanya. Repository yang
            // mengurus transaksi terpisahnya.
            approvals.markExpired(approvalId, current.revision + 1)
            throw ConflictException("approval has expired")
        }

        val tierBeforeDecision = current.currentTier()
        val delegatedFrom = approvals.delegations(current.tenantId)
            .firstOrNull { it.delegateId == command.approverId && it.validUntil.isAfter(now) && it.approverId in (tierBeforeDecision?.approverIds ?: emptySet()) }
            ?.approverId
        val tier = tierBeforeDecision ?: throw ConflictException("approval is already complete")
        val effectiveApprover = delegatedFrom ?: command.approverId
        // Empat mata: pemohon dan pemegang barang tidak boleh menyetujui permintaannya sendiri.
        if (command.approverId == current.requesterId || command.approverId == current.custodianId || effectiveApprover !in tier.approverIds) {
            throw ValidationException("approver is not independent and authorized for this tier")
        }

        val snapshot = InventoryApprovalDecisionSnapshot(
            UuidV7.generate(), tier.number, command.approverId, delegatedFrom, command.decision,
            command.reason, now, current.revision + 1, command.operationKey, command.operationHash,
        )
        val decisions = current.decisions + snapshot
        val status = when {
            command.decision == InventoryApprovalDecision.REJECT ->
                // Selisih stock opname yang ditolak BUKAN berarti permintaannya salah — biasanya
                // hitungannya yang perlu diulang, jadi ia dikembalikan ke pemohon, bukan dimatikan.
                if (current.type == InventoryApprovalType.COUNT_VARIANCE) InventoryApprovalStatus.REWORK_REQUIRED else InventoryApprovalStatus.REJECTED
            decisions.count { it.decision == InventoryApprovalDecision.APPROVE } >= current.policy.requiredTiers(current.amount).size -> InventoryApprovalStatus.APPROVED
            else -> InventoryApprovalStatus.PENDING
        }
        val updated = current.copy(status = status, revision = current.revision + 1, decisions = decisions)
        approvals.appendDecision(approvalId, snapshot)
        approvals.updateStatus(approvalId, updated)

        if (status != InventoryApprovalStatus.PENDING) emitEffect(updated, now)
        return updated
    }

    /**
     * Sapu permintaan yang sudah lewat tenggat menjadi EXPIRED dan batalkan mutasi yang
     * digantungnya.
     *
     * Tanpa ini, permintaan yang tak pernah disentuh approver menggantung PENDING selamanya
     * di antrean — dan yang lebih berbahaya, mutasi stok terkaitnya tetap PENDING_APPROVAL
     * sehingga barangnya tidak pernah benar-benar masuk MAUPUN dilepaskan. Dipanggil
     * [InventoryApprovalExpiryWorker] per tenant.
     */
    @Transactional
    fun expireOverdue(tenantId: UUID, now: Instant = Instant.now(clock)): List<UUID> {
        val overdue = approvals.findPending(tenantId).filter { now >= it.expiresAt }
        return overdue.mapNotNull { request ->
            approvals.markExpired(request.approvalId, request.revision + 1)
            val expired = request.copy(status = InventoryApprovalStatus.EXPIRED, revision = request.revision + 1)
            emitEffect(expired, now)
            request.approvalId
        }
    }

    @Transactional(readOnly = true)
    fun get(approvalId: UUID): InventoryApprovalRequest? = approvals.findById(approvalId)

    @Transactional(readOnly = true)
    fun pendingForCurrentActor(): List<InventoryApprovalRequest> {
        val actor = currentUser?.currentOrNull() ?: throw ValidationException("approval actor is required")
        return approvals.findPending(actor.tenantId).filter { request ->
            request.currentTier()?.approverIds?.contains(actor.userId) == true &&
                actor.userId != request.requesterId && actor.userId != request.custodianId
        }
    }

    @Transactional(readOnly = true)
    fun effects(tenantId: UUID): List<InventoryApprovalEffect> = approvals.effects(tenantId)

    /**
     * Terbitkan efek TEPAT sekali, lalu jalankan akibatnya pada ledger.
     *
     * UNIQUE (tenant_id, approval_id) pada `inventory_approval_effect` yang menjamin
     * ketepatan sekali itu; mutasi stok hanya digerakkan kalau baris efeknya memang baru,
     * supaya retry tidak memotong stok dua kali.
     */
    private fun emitEffect(request: InventoryApprovalRequest, at: Instant) {
        val effect = InventoryApprovalEffect(
            request.approvalId, request.tenantId, request.type, request.status, request.movementId,
            request.operationKey, at,
        )
        if (!approvals.recordEffect(effect)) return
        executeEffect(request)
        events?.publishEvent(
            InventoryApprovalDecisionEvent(
                request.tenantId, request.approvalId, request.type, request.status,
                request.movementId, request.operationKey,
            ),
        )
    }

    /**
     * Tutup loop approval -> mutasi: APPROVED memberlakukan mutasi yang digantung,
     * REJECTED/EXPIRED mematikannya.
     *
     * Kalau langkah ini tidak ada, "disetujui" cuma label. Mutasi restock tetap
     * PENDING_APPROVAL, saldo tidak bertambah sebaris pun, dan petugas gudang baru sadar
     * saat barang fisik di rak tidak cocok dengan angka di layar — berbulan-bulan kemudian.
     * Sebaliknya penolakan yang tidak mematikan mutasi meninggalkan mutasi hantu yang bisa
     * disahkan siapa saja lewat jalur lain.
     */
    private fun executeEffect(request: InventoryApprovalRequest) {
        val movementId = request.movementId ?: return
        // Kegagalan di sini SENGAJA dibiarkan naik, tidak ditelan.
        //
        // Menelannya tidak pernah bisa bekerja: [ledger] adalah bean ter-proxy dan
        // `approvePending`/`rejectPending` bertanda `@Transactional` propagasi default, jadi
        // keduanya IKUT transaksi milik pemanggil. Begitu salah satunya melempar, proxy-nya
        // memanggil `setRollbackOnly()` pada transaksi bersama itu. Menangkap exception-nya
        // hanya menyembunyikan sebabnya: method ini selesai seolah sukses, lalu commit-nya
        // meledak `UnexpectedRollbackException` — keputusan approval TETAP hilang (justru
        // yang mau diselamatkan), dan approver menerima pesan yang tak berarti apa pun
        // baginya alih-alih "stok tidak cukup".
        //
        // Dengan dibiarkan naik, seluruh keputusan batal secara utuh: permintaannya tetap
        // PENDING dan bisa diputuskan lagi setelah stoknya benar. Tidak ada celah audit —
        // approval yang efeknya tak pernah berlaku bukan bukti apa-apa selain percobaan
        // yang gagal, dan itu tempatnya di log, bukan di mesin status approval.
        log.debug("Menerapkan efek persetujuan {} ({}) pada mutasi {}", request.approvalId, request.status, movementId)
        when (request.status) {
            InventoryApprovalStatus.APPROVED -> ledger.approvePending(movementId)
            InventoryApprovalStatus.REJECTED, InventoryApprovalStatus.EXPIRED -> ledger.rejectPending(movementId)
            else -> null
        }
    }

    /**
     * Tulis jejak override darurat SEBELUM efeknya berlaku, di transaksi yang sama.
     *
     * `AuditRecorder` juga dipanggil supaya override muncul di linimasa audit global, tapi
     * ia TIDAK cukup sendirian: publikasinya ditulis modul audit pada fase AFTER_COMMIT dan
     * kegagalannya SENGAJA ditelan agar tidak menggagalkan operasi bisnis. Untuk audit biasa
     * itu benar; untuk satu-satunya jejak bahwa empat-mata dilangkahi, itu berarti override
     * bisa berhasil tanpa bekas.
     */
    private fun recordEmergency(request: InventoryApprovalRequest, matrix: InventoryApprovalPolicyMatrix, at: Instant) {
        val reason = request.emergencyReason ?: return
        val bypassed = matrix.bypassedTiers(request.amount)
        emergencyAudit.recordEmergency(
            EmergencyOverrideAudit(
                request.tenantId, request.approvalId, request.type, request.amount,
                request.requesterId, request.custodianId, reason, bypassed, at,
            ),
        )
        auditor?.record(
            "inventory.approval.emergency", "InventoryApproval", request.approvalId, request.tenantId,
            mapOf(
                "type" to request.type.name,
                "amount" to request.amount,
                "reason" to reason,
                "bypassedTiers" to bypassed,
                "movementId" to request.movementId?.toString(),
            ),
        )
    }

    private fun replayOrConflict(stored: InventoryApprovalRequest, operationHash: String): InventoryApprovalRequest {
        if (stored.operationHash != operationHash) throw ConflictException("approval operation key was used with a different payload")
        return stored
    }
}

/**
 * Permintaan persetujuan: hanya menyebut APA yang diminta.
 *
 * Perhatikan yang HILANG dibanding versi sebelumnya: `policy` dan `policySnapshotHash`.
 * Keduanya sekarang milik server ([InventoryApprovalPolicyService]) — itu inti dari
 * perubahan ini, bukan efek samping refactor.
 */
data class CreateInventoryApproval(
    val tenantId: UUID,
    val type: InventoryApprovalType,
    val amount: Long,
    val requesterId: UUID,
    val custodianId: UUID?,
    val operationKey: String,
    val operationHash: String,
    /** Mutasi PENDING_APPROVAL yang akan diberlakukan begitu permintaan ini disetujui. */
    val movementId: UUID? = null,
    val emergencyReason: String? = null,
)

data class DecideInventoryApproval(
    val tenantId: UUID,
    val approverId: UUID,
    val decision: InventoryApprovalDecision,
    val operationKey: String,
    val operationHash: String,
    val reason: String? = null,
)
