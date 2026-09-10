package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.domain.model.InventoryApprovalDecision
import java.time.Instant
import java.util.UUID

enum class WarehouseApprovalStatus { PENDING, APPROVED, REJECTED, REWORK_REQUIRED, EXPIRED, STALE }
data class WarehouseApprovalDecisionInput(val requestId: UUID, val expectedRevision: Long,
    val decision: InventoryApprovalDecision, val reason: String? = null, val evidenceReference: UUID? = null)
data class WarehouseApprovalReworkInput(val requestId: UUID, val expectedRevision: Long)
data class WarehouseApprovalView(val requestId: UUID, val sourceDocumentId: UUID, val sourceRevision: Long,
    val status: WarehouseApprovalStatus, val revision: Long, val expiresAt: Instant, val code: String,
    val effectOperationId: UUID? = null)
data class WarehouseApprovalResponse(val status: Int, val body: String)
data class WarehouseApprovalSnapshot(val evaluation: WarehousePolicyEvaluation, val source: String,
    val sourceHash: String, val locations: Set<UUID>, val requesterId: UUID, val code: String, val cutoverEpoch: Long)
data class WarehouseApprovalRecord(val id: UUID, val snapshot: WarehouseApprovalSnapshot, val status: WarehouseApprovalStatus,
    val revision: Long, val requestedAt: Instant, val expiresAt: Instant, val terminalBody: String?)
data class WarehouseApprovalDecisionRecord(val id: UUID, val tier: Int, val actorId: UUID, val decision: InventoryApprovalDecision,
    val reason: String?, val decidedAt: Instant, val revision: Long, val delegation: WarehouseDelegation?, val authorityEpoch: Long,
    val evidenceReference: UUID?)
data class WarehouseApprovalAttempt(val requestId: UUID, val sourceDocumentId: UUID, val sourceRevision: Long,
    val policyVersionId: UUID, val requestRevision: Long, val tier: Int, val decision: InventoryApprovalDecision,
    val delegation: WarehouseDelegation?)

enum class WarehouseApprovalStage { REQUEST, REQUIREMENTS, DECISION, OWNER_EFFECT, INBOX, EFFECT_RECEIPT, RESPONSE }
fun interface WarehouseApprovalProbe { fun reached(stage: WarehouseApprovalStage, requestId: UUID) }
