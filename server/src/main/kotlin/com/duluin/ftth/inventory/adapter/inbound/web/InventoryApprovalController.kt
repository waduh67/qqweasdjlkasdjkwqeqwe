package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/inventory/approvals")
class InventoryApprovalController(
    private val approvals: InventoryApprovalService,
    private val policies: InventoryApprovalPolicyService,
    private val currentUser: CurrentUserProvider,
) {
    @GetMapping("/pending")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun pending(): List<InventoryApprovalRequest> = approvals.pendingForCurrentActor()

    @PostMapping
    @PreAuthorize("@authz.can('inventory.approval.request')")
    fun request(@Valid @RequestBody body: ApprovalRequestBody): InventoryApprovalRequest {
        val actor = currentUser.current()
        return approvals.request(body.toCommand(actor.tenantId, actor.userId))
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("@authz.can('inventory.approval.decide')")
    fun decide(@PathVariable id: UUID, @Valid @RequestBody body: ApprovalDecisionBody): InventoryApprovalRequest {
        val actor = currentUser.current()
        return approvals.decide(id, DecideInventoryApproval(actor.tenantId, actor.userId, body.decision, body.operationKey, body.operationHash, body.reason))
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun get(@PathVariable id: UUID): InventoryApprovalRequest =
        approvals.get(id) ?: throw NotFoundException("Permintaan persetujuan tidak ditemukan")

    /**
     * Matriks yang BERLAKU, bukan sekadar yang tersimpan: tipe yang belum pernah dikonfigurasi
     * tetap muncul dengan bawaan dari kode. Kalau hanya baris tersimpan yang ditampilkan,
     * tenant baru melihat halaman kosong dan menyangka tidak ada persetujuan yang berlaku.
     */
    @GetMapping("/policies")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun policies(): List<ApprovalPolicyView> {
        val actor = currentUser.current()
        return policies.effectiveMatrix(actor.tenantId).map { ApprovalPolicyView.of(it) }
    }

    @PutMapping("/policies/{type}")
    @PreAuthorize("@authz.can('inventory.approval.manage')")
    fun configurePolicy(@PathVariable type: InventoryApprovalType, @Valid @RequestBody body: ApprovalPolicyBody): ApprovalPolicyView {
        val actor = currentUser.current()
        return ApprovalPolicyView.of(policies.configure(body.toMatrix(actor.tenantId, type)))
    }

    /**
     * Laporan override darurat. Dibuka untuk pemegang `inventory.approval.view` — pengawasan
     * atas kontrol empat-mata yang dilangkahi tidak boleh hanya terlihat oleh orang yang juga
     * berhak melangkahinya.
     */
    @GetMapping("/emergency-overrides")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun emergencyOverrides(): List<EmergencyOverrideView> {
        val actor = currentUser.current()
        return policies.emergencyOverrides(actor.tenantId).map { EmergencyOverrideView.of(it) }
    }
}

/**
 * Permintaan persetujuan hanya menyebut APA yang diminta.
 *
 * Yang SENGAJA tidak ada lagi di sini: `tiers`, `expiryHours`, dan `policySnapshotHash`.
 * Selama ketiganya datang dari klien, orang yang mengajukan penghapusbukuan aset senilai
 * ratusan juta juga menuliskan sendiri bahwa cukup satu tier dengan approver dirinya sendiri —
 * dan kontrol empat-mata ini tidak menahan apa pun. Sekarang server yang membacanya dari
 * `inventory_approval_policy`.
 */
data class ApprovalRequestBody(
    val type: InventoryApprovalType,
    @field:PositiveOrZero val amount: Long,
    val custodianId: UUID?,
    /** Mutasi PENDING_APPROVAL yang akan diberlakukan begitu permintaan ini disetujui. */
    val movementId: UUID? = null,
    val emergencyReason: String? = null,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val operationHash: String,
) {
    fun toCommand(tenantId: UUID, requesterId: UUID) = CreateInventoryApproval(
        tenantId, type, amount, requesterId, custodianId, operationKey, operationHash, movementId, emergencyReason,
    )
}

data class ApprovalDecisionBody(
    val decision: InventoryApprovalDecision,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val operationHash: String,
    val reason: String? = null,
    /**
     * Diterima demi kompatibilitas klien lama tapi DIABAIKAN: mutasi yang diotorisasi sudah
     * diikat saat permintaan dibuat. Kalau approver masih boleh menentukannya di sini, ia bisa
     * menyetujui permintaan A sambil memberlakukan mutasi B yang tidak pernah dinilai siapa pun.
     */
    @Deprecated("Diabaikan server; mutasi diikat pada permintaan") val movementId: UUID? = null,
)

data class ApprovalPolicyBody(
    @field:NotEmpty val tiers: List<ApprovalTierBody>,
    val expiryHours: Long = 24,
    val emergencyAllowed: Boolean = false,
) {
    fun toMatrix(tenantId: UUID, type: InventoryApprovalType) = InventoryApprovalPolicyMatrix(
        tenantId, type, Duration.ofHours(expiryHours), emergencyAllowed,
        tiers.map { ApprovalTierRule(it.number, it.minimumAmount, it.approverRole, it.approverIds) },
    )
}

data class ApprovalTierBody(
    val number: Int,
    @field:PositiveOrZero val minimumAmount: Long,
    @field:NotBlank val approverRole: String,
    val approverIds: Set<UUID> = emptySet(),
)

data class ApprovalPolicyView(
    val type: InventoryApprovalType,
    val expiryHours: Long,
    val emergencyAllowed: Boolean,
    val configured: Boolean,
    val tiers: List<ApprovalTierView>,
) {
    companion object {
        fun of(matrix: InventoryApprovalPolicyMatrix) = ApprovalPolicyView(
            matrix.type, matrix.expiry.toHours(), matrix.emergencyAllowed,
            // Tier bawaan belum punya approver konkret; bendera ini yang memberi tahu UI bahwa
            // permintaan bertipe ini akan DITOLAK sampai administrator mengisinya.
            matrix.tiers.all { it.approverIds.isNotEmpty() },
            matrix.tiers.map { ApprovalTierView(it.number, it.minimumAmount, it.approverRole, it.approverIds) },
        )
    }
}

data class ApprovalTierView(val number: Int, val minimumAmount: Long, val approverRole: String, val approverIds: Set<UUID>)

data class EmergencyOverrideView(
    val approvalId: UUID,
    val type: InventoryApprovalType,
    val amount: Long,
    val requesterId: UUID,
    val custodianId: UUID?,
    val reason: String,
    val bypassedTiers: List<Int>,
    val occurredAt: Instant,
) {
    companion object {
        fun of(entry: EmergencyOverrideAudit) = EmergencyOverrideView(
            entry.approvalId, entry.type, entry.amount, entry.requesterId, entry.custodianId,
            entry.reason, entry.bypassedTiers, entry.occurredAt,
        )
    }
}
