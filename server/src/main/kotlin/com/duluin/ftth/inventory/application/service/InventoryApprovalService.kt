package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.domain.model.*
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.context.ApplicationEventPublisher
import com.duluin.ftth.inventory.InventoryApprovalDecisionEvent
import com.duluin.ftth.common.security.CurrentUserProvider

@Service
class InventoryApprovalService(
    private val clock: Clock = Clock.systemUTC(),
    private val events: ApplicationEventPublisher? = null,
    private val currentUser: CurrentUserProvider? = null,
) {
    private val monitor = Any()
    private val requests = linkedMapOf<UUID, InventoryApprovalRequest>()
    private val byOperation = mutableMapOf<Pair<UUID, String>, InventoryApprovalRequest>()
    private val delegations = mutableListOf<ApproverDelegation>()
    private val effects = linkedMapOf<UUID, InventoryApprovalEffect>()

    fun registerDelegation(delegation: ApproverDelegation) = synchronized(monitor) { delegations += delegation }

    fun request(command: CreateInventoryApproval): InventoryApprovalRequest = synchronized(monitor) {
        currentUser?.current()?.let {
            if (it.tenantId != command.tenantId || it.userId != command.requesterId) throw ValidationException("approval actor does not match server context")
            if (command.emergencyReason != null && !it.hasPermission("inventory.approval.emergency")) throw ValidationException("emergency approval permission is required")
        }
        val prior = byOperation[command.tenantId to command.operationKey]
        if (prior != null) {
            if (prior.operationHash != command.operationHash) throw ConflictException("approval operation key was used with a different payload")
            return@synchronized prior
        }
        if (command.emergencyReason != null && !command.policy.emergencyAllowed) throw ValidationException("emergency approval is not enabled")
        val now = Instant.now(clock)
        val request = InventoryApprovalRequest(UUID.randomUUID(), command.tenantId, command.type, command.amount, command.requesterId, command.custodianId, command.policy, command.policySnapshotHash, command.operationKey, command.operationHash, command.emergencyReason, now, now.plus(command.policy.expiry))
        requests[request.approvalId] = request
        byOperation[command.tenantId to command.operationKey] = request
        request
    }

    fun decide(approvalId: UUID, command: DecideInventoryApproval): InventoryApprovalRequest = synchronized(monitor) {
        val current = requests[approvalId] ?: throw ValidationException("approval does not exist")
        if (current.tenantId != command.tenantId) throw ValidationException("approval belongs to another tenant")
        val duplicate = current.decisions.firstOrNull { it.approverId == command.approverId && it.operationKey == command.operationKey }
        if (duplicate != null) {
            if (duplicate.operationHash != command.operationHash) throw ConflictException("decision operation key was used with a different payload")
            return@synchronized current
        }
        val now = Instant.now(clock)
        if (now >= current.expiresAt && current.status == InventoryApprovalStatus.PENDING) {
            val expired = current.copy(status = InventoryApprovalStatus.EXPIRED, revision = current.revision + 1)
            requests[approvalId] = expired
            throw ConflictException("approval has expired")
        }
        val delegatedFrom = delegations.firstOrNull { it.delegateId == command.approverId && it.validUntil.isAfter(now) && it.approverId in (current.currentTier()?.approverIds ?: emptySet()) }?.approverId
        val tier = current.currentTier() ?: throw ConflictException("approval is already complete")
        val effectiveApprover = if (delegatedFrom != null) delegatedFrom else command.approverId
        if (command.approverId == current.requesterId || command.approverId == current.custodianId || effectiveApprover !in tier.approverIds) throw ValidationException("approver is not independent and authorized for this tier")
        val snapshot = InventoryApprovalDecisionSnapshot(UUID.randomUUID(), tier.number, command.approverId, delegatedFrom, command.decision, command.reason, now, current.revision + 1, command.operationKey, command.operationHash)
        val decisions = current.decisions + snapshot
        val status = when {
            command.decision == InventoryApprovalDecision.REJECT -> if (current.type == InventoryApprovalType.COUNT_VARIANCE) InventoryApprovalStatus.REWORK_REQUIRED else InventoryApprovalStatus.REJECTED
            decisions.count { it.decision == InventoryApprovalDecision.APPROVE } >= current.policy.requiredTiers(current.amount).size -> InventoryApprovalStatus.APPROVED
            else -> InventoryApprovalStatus.PENDING
        }
        val updated = current.copy(status = status, revision = current.revision + 1, decisions = decisions)
        requests[approvalId] = updated
        if (status != InventoryApprovalStatus.PENDING) {
            val effect = InventoryApprovalEffect(approvalId, updated.tenantId, updated.type, status, command.movementId, updated.operationKey, now)
            if (effects.putIfAbsent(approvalId, effect) == null) {
                events?.publishEvent(InventoryApprovalDecisionEvent(updated.tenantId, approvalId, updated.type, status, command.movementId, updated.operationKey))
            }
        }
        updated
    }

    fun get(approvalId: UUID): InventoryApprovalRequest? = synchronized(monitor) { requests[approvalId] }
    fun pendingForCurrentActor(): List<InventoryApprovalRequest> = synchronized(monitor) {
        val actor = currentUser?.current() ?: throw ValidationException("approval actor is required")
        requests.values.filter { request ->
            request.tenantId == actor.tenantId &&
                request.status == InventoryApprovalStatus.PENDING &&
                request.currentTier()?.approverIds?.contains(actor.userId) == true &&
                actor.userId != request.requesterId && actor.userId != request.custodianId
        }
    }
    fun effects(tenantId: UUID): List<InventoryApprovalEffect> = synchronized(monitor) { effects.values.filter { it.tenantId == tenantId } }
}

data class CreateInventoryApproval(
    val tenantId: UUID, val type: InventoryApprovalType, val amount: Long, val requesterId: UUID, val custodianId: UUID?, val policy: InventoryApprovalPolicy, val policySnapshotHash: String, val operationKey: String, val operationHash: String, val emergencyReason: String? = null,
)

data class DecideInventoryApproval(
    val tenantId: UUID, val approverId: UUID, val decision: InventoryApprovalDecision, val operationKey: String, val operationHash: String, val reason: String? = null, val movementId: UUID? = null,
)
