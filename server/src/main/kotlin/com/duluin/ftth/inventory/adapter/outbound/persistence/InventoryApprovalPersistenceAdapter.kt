package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.inventory.application.port.outbound.InventoryApprovalRepository
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

/**
 * Menyimpan permintaan, keputusan, efek, dan delegasi persetujuan gudang.
 *
 * Kebijakan approval (daftar tier + approver) disimpan sebagai SNAPSHOT jsonb, bukan
 * referensi ke tabel kebijakan: kalau matriksnya berubah di tengah rantai persetujuan,
 * permintaan yang sudah berjalan harus tetap dinilai dengan aturan saat ia diajukan —
 * kalau tidak, mengubah matriks bisa membuat permintaan lama tiba-tiba dianggap lunas.
 */
@Component
class InventoryApprovalPersistenceAdapter(
    private val approvals: InventoryApprovalJpaRepository,
    private val decisions: InventoryApprovalDecisionJpaRepository,
    private val effects: InventoryApprovalEffectJpaRepository,
    private val delegations: InventoryApprovalDelegationJpaRepository,
    private val mapper: ObjectMapper,
    txManager: PlatformTransactionManager,
) : InventoryApprovalRepository {

    private val ownTransaction = TransactionTemplate(txManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun findById(approvalId: UUID): InventoryApprovalRequest? =
        approvals.findById(approvalId).orElse(null)?.toDomain()

    override fun findByOperation(tenantId: UUID, operationKey: String): InventoryApprovalRequest? =
        approvals.findByTenantIdAndOperationKey(tenantId, operationKey)?.toDomain()

    override fun findPending(tenantId: UUID): List<InventoryApprovalRequest> =
        approvals.findAllByTenantIdAndStatusOrderByRequestedAt(tenantId, InventoryApprovalStatus.PENDING).map { it.toDomain() }

    override fun appendIfAbsent(request: InventoryApprovalRequest): InventoryApprovalRequest? {
        val inserted = approvals.insertIfAbsent(
            request.approvalId, request.tenantId, request.type.name, request.amount, request.requesterId,
            request.custodianId, request.policy.version, mapper.writeValueAsString(request.policy.toSnapshot()),
            request.policySnapshotHash, request.operationKey, request.operationHash, request.emergencyReason,
            request.requestedAt, request.expiresAt, request.status.name, request.revision,
        )
        if (inserted == 0) {
            return approvals.findByTenantIdAndOperationKey(request.tenantId, request.operationKey)?.toDomain()
                ?: throw NotFoundException("Permintaan persetujuan hilang saat sisipan bersamaan")
        }
        return null
    }

    override fun updateStatus(approvalId: UUID, request: InventoryApprovalRequest) {
        val entity = approvals.findById(approvalId).orElse(null) ?: throw NotFoundException("Permintaan persetujuan tidak ditemukan")
        entity.status = request.status
        entity.revision = request.revision
        approvals.save(entity)
    }

    /**
     * Transaksi TERPISAH dan mandiri — lihat alasannya di
     * [InventoryApprovalRepository.markExpired]. Koneksinya tetap membawa `app.tenant_id`
     * karena [TransactionDefinition.PROPAGATION_REQUIRES_NEW] men-checkout koneksi baru di
     * dalam `TenantContext` yang sama.
     */
    override fun markExpired(approvalId: UUID, revision: Long) {
        ownTransaction.executeWithoutResult {
            val entity = approvals.findById(approvalId).orElse(null) ?: return@executeWithoutResult
            if (entity.status != InventoryApprovalStatus.PENDING) return@executeWithoutResult
            entity.status = InventoryApprovalStatus.EXPIRED
            entity.revision = revision
            approvals.save(entity)
        }
    }

    override fun appendDecision(approvalId: UUID, snapshot: InventoryApprovalDecisionSnapshot) {
        decisions.save(
            InventoryApprovalDecisionJpaEntity(
                snapshot.decisionId, approvalId, snapshot.tier, snapshot.approverId, snapshot.delegatedFrom,
                snapshot.decision, snapshot.reason, snapshot.decidedAt, snapshot.revision,
                snapshot.operationKey, snapshot.operationHash,
            ),
        )
    }

    override fun delegations(tenantId: UUID): List<ApproverDelegation> =
        delegations.findAllByTenantId(tenantId).map { ApproverDelegation(it.approverId, it.delegateId, it.validUntil) }

    /**
     * Diperbarui di tempat kalau pasangan approver/delegate-nya sudah ada: kalau setiap
     * penyimpanan membuat baris baru, pencabutan delegasi (memajukan `valid_until`) tidak akan
     * pernah berlaku karena baris lama yang masih panjang masa berlakunya tetap ikut terbaca.
     */
    override fun saveDelegation(delegation: ApproverDelegation) {
        val existing = delegations.findByApproverIdAndDelegateId(delegation.approverId, delegation.delegateId)
        if (existing == null) {
            delegations.save(
                InventoryApprovalDelegationJpaEntity(UuidV7.generate(), delegation.approverId, delegation.delegateId, delegation.validUntil),
            )
            return
        }
        existing.validUntil = delegation.validUntil
        delegations.save(existing)
    }

    override fun recordEffect(effect: InventoryApprovalEffect): Boolean = effects.insertIfAbsent(
        UuidV7.generate(), effect.tenantId, effect.approvalId, effect.type.name, effect.status.name,
        effect.movementId, effect.operationKey, effect.emittedAt,
    ) == 1

    override fun effects(tenantId: UUID): List<InventoryApprovalEffect> =
        effects.findAllByTenantIdOrderByEmittedAt(tenantId).map {
            InventoryApprovalEffect(it.approvalId, it.tenantId!!, it.approvalType, it.status, it.movementId, it.operationKey, it.emittedAt)
        }

    private fun InventoryApprovalJpaEntity.toDomain(): InventoryApprovalRequest {
        val snapshot = mapper.readValue(policySnapshot, PolicySnapshotJson::class.java)
        return InventoryApprovalRequest(
            id, tenantId!!, approvalType, amount, requesterId, custodianId, snapshot.toPolicy(),
            policySnapshotHash, operationKey, operationHash, emergencyReason, requestedAt, expiresAt,
            status, revision,
            decisions.findAllForApproval(tenantId!!, id).map {
                InventoryApprovalDecisionSnapshot(
                    it.id, it.tier, it.approverId, it.delegatedFrom, it.decision, it.reason,
                    it.decidedAt, it.revision, it.operationKey, it.operationHash,
                )
            },
        )
    }
}

/**
 * Bentuk JSON kebijakan SENGAJA dieja sebagai DTO sendiri, bukan serialisasi langsung
 * [InventoryApprovalPolicy]: kolom ini adalah data historis yang harus tetap bisa dibaca
 * bertahun-tahun kemudian, jadi bentuknya tidak boleh ikut berubah setiap kali model domain
 * di-refactor.
 */
internal data class PolicySnapshotJson(
    val version: Long = 1,
    val expirySeconds: Long = 86_400,
    val emergencyAllowed: Boolean = false,
    val tiers: List<PolicyTierJson> = emptyList(),
) {
    fun toPolicy() = InventoryApprovalPolicy(
        version,
        tiers.map { ApprovalTier(it.number, it.minimumAmount, it.approverIds.toSet()) },
        Duration.ofSeconds(expirySeconds),
        emergencyAllowed,
    )
}

internal data class PolicyTierJson(
    val number: Int = 1,
    val minimumAmount: Long = 0,
    val approverIds: List<UUID> = emptyList(),
)

internal fun InventoryApprovalPolicy.toSnapshot() = PolicySnapshotJson(
    version, expiry.seconds, emergencyAllowed,
    tiers.map { PolicyTierJson(it.number, it.minimumAmount, it.approverIds.toList()) },
)
