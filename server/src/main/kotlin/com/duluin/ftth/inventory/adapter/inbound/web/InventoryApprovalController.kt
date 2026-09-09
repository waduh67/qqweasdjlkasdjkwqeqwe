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
    private val policy: com.duluin.ftth.inventory.WarehousePolicyEvaluationApi,
) {
    @GetMapping("/pending")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun pending(): List<InventoryApprovalRequest> = approvals.pendingForCurrentActor()

    @PostMapping
    @PreAuthorize("@authz.can('inventory.approval.request')")
    fun request(@RequestBody body: String): org.springframework.http.ResponseEntity<com.duluin.ftth.inventory.WarehousePolicyEvaluation> {
        val source = WarehouseReceiptJson.decode(body, com.duluin.ftth.inventory.WarehouseSourceInput::class.java)
        return org.springframework.http.ResponseEntity.status(409).body(policy.evaluate(source))
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("@authz.can('inventory.approval.decide')")
    fun decide(@PathVariable id: UUID, @RequestBody body: String): Nothing {
        WarehouseReceiptJson.decode(body, SafeApprovalDecisionBody::class.java)
        throw com.duluin.ftth.inventory.WarehouseContractException(com.duluin.ftth.inventory.WarehouseError(
            com.duluin.ftth.inventory.WarehouseErrorCode.APPROVAL_REQUIRED, "Use the durable document approval workflow; decisions are not available from legacy requests"))
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun get(@PathVariable id: UUID): InventoryApprovalRequest = approvals.get(id) ?: error("approval not found")
}

data class SafeApprovalDecisionBody(val expectedRevision: Long, val decision: InventoryApprovalDecision, val reason: String? = null)
