package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/api/inventory/approvals")
class InventoryApprovalController(
    private val approvals: InventoryApprovalService,
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
        return approvals.decide(id, DecideInventoryApproval(actor.tenantId, actor.userId, body.decision, body.operationKey, body.operationHash, body.reason, body.movementId))
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun get(@PathVariable id: UUID): InventoryApprovalRequest = approvals.get(id) ?: error("approval not found")
}

data class ApprovalRequestBody(
    val type: InventoryApprovalType,
    @field:PositiveOrZero val amount: Long,
    val custodianId: UUID?,
    val tiers: List<ApprovalTierBody>,
    val expiryHours: Long = 24,
    val emergencyReason: String? = null,
    @field:NotBlank val policySnapshotHash: String,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val operationHash: String,
) {
    fun toCommand(tenantId: UUID, requesterId: UUID) = CreateInventoryApproval(tenantId, type, amount, requesterId, custodianId, InventoryApprovalPolicy(1, tiers.map { it.toTier() }, Duration.ofHours(expiryHours), emergencyReason != null), policySnapshotHash, operationKey, operationHash, emergencyReason)
}

data class ApprovalTierBody(val number: Int, val minimumAmount: Long, val approverIds: Set<UUID>) {
    fun toTier() = ApprovalTier(number, minimumAmount, approverIds)
}

data class ApprovalDecisionBody(
    val decision: InventoryApprovalDecision,
    @field:NotBlank val operationKey: String,
    @field:NotBlank val operationHash: String,
    val reason: String? = null,
    val movementId: UUID? = null,
)
