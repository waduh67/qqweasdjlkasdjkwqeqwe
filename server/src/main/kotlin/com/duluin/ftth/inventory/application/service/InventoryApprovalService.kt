package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.InventoryApprovalDecisionEvent
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalRepository
import com.duluin.ftth.inventory.domain.model.*
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
 */
@Service
class InventoryApprovalService(
    private val approvals: InventoryApprovalRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val events: ApplicationEventPublisher? = null,
    private val currentUser: CurrentUserProvider? = null,
) {
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
        if (command.emergencyReason != null && !command.policy.emergencyAllowed) throw ValidationException("emergency approval is not enabled")

        val now = Instant.now(clock)
        val request = InventoryApprovalRequest(
            UuidV7.generate(), command.tenantId, command.type, command.amount, command.requesterId,
            command.custodianId, command.policy, command.policySnapshotHash, command.operationKey,
            command.operationHash, command.emergencyReason, now, now.plus(command.policy.expiry),
        )
        // Penjaga sebenarnya terhadap dua request bersamaan adalah UNIQUE
        // (tenant_id, operation_key); yang kalah menerima baris pemenang sebagai replay.
        val raced = approvals.appendIfAbsent(request) ?: return request
        return replayOrConflict(raced, command.operationHash)
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

        if (status != InventoryApprovalStatus.PENDING) {
            val effect = InventoryApprovalEffect(approvalId, updated.tenantId, updated.type, status, command.movementId, updated.operationKey, now)
            // UNIQUE (tenant_id, approval_id) pada `inventory_approval_effect` yang menjamin
            // satu approval memancarkan efek TEPAT sekali; event hanya ikut kalau baris efeknya
            // memang baru, supaya retry tidak memicu mutasi stok dua kali.
            if (approvals.recordEffect(effect)) {
                events?.publishEvent(InventoryApprovalDecisionEvent(updated.tenantId, approvalId, updated.type, status, command.movementId, updated.operationKey))
            }
        }
        return updated
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

    private fun replayOrConflict(stored: InventoryApprovalRequest, operationHash: String): InventoryApprovalRequest {
        if (stored.operationHash != operationHash) throw ConflictException("approval operation key was used with a different payload")
        return stored
    }
}

data class CreateInventoryApproval(
    val tenantId: UUID, val type: InventoryApprovalType, val amount: Long, val requesterId: UUID, val custodianId: UUID?, val policy: InventoryApprovalPolicy, val policySnapshotHash: String, val operationKey: String, val operationHash: String, val emergencyReason: String? = null,
)

data class DecideInventoryApproval(
    val tenantId: UUID, val approverId: UUID, val decision: InventoryApprovalDecision, val operationKey: String, val operationHash: String, val reason: String? = null, val movementId: UUID? = null,
)
