package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.DurableApprovalService
import com.duluin.ftth.inventory.application.service.LegacyApprovalQuery
import com.duluin.ftth.inventory.domain.model.InventoryApprovalDecision
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.util.MultiValueMap
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/approvals", "/api/inventory/approvals")
class InventoryApprovalController(private val approvals: DurableApprovalService, private val legacy: LegacyApprovalQuery,
    private val queries: InventoryApprovalQueryApi) {
    @GetMapping
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun list(@RequestParam parameters: MultiValueMap<String, String>): WarehousePage<WarehouseApprovalView> {
        val result = queries.list(WarehouseApprovalFilters.parse(parameters))
        return WarehousePage(result.items.map { it.approval }, result.page, result.size, result.totalElements)
    }

    @GetMapping("/pending")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun pending() = legacy.pending()

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun get(@PathVariable id: UUID, request: jakarta.servlet.http.HttpServletRequest): Any =
        if (request.requestURI.startsWith("/api/inventory/")) legacy.get(id) else approvals.get(id)

    @GetMapping("/{id}/history")
    @PreAuthorize("@authz.can('inventory.approval.view')")
    fun history(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): List<Map<String, Any?>> {
        val filter = WarehouseApprovalFilters.parse(parameters, history = true)
        return queries.history(id, WarehousePageRequest(filter.page, filter.size)).items.sortedBy { it.revision }.map { decision ->
        mapOf("id" to decision.id, "tier" to decision.tier, "approverId" to decision.approver.id, "decision" to decision.decision,
            "reason" to decision.reason, "decidedAt" to decision.decidedAt, "revision" to decision.revision,
            "delegatedFrom" to decision.delegatedFrom?.id, "evidenceReference" to decision.evidenceReference)
        }
    }

    @PostMapping("", "/request")
    @PreAuthorize("@authz.can('inventory.approval.request')")
    fun request(@RequestBody body: String, @RequestHeader("Idempotency-Key") key: String) =
        response(approvals.request(WarehouseReceiptJson.decode(body, WarehouseSourceInput::class.java), key))

    @PostMapping("/decide")
    @PreAuthorize("@authz.can('inventory.approval.decide')")
    fun decide(@RequestBody body: String, @RequestHeader("Idempotency-Key") key: String) =
        response(approvals.decide(WarehouseReceiptJson.decode(body, WarehouseApprovalDecisionInput::class.java), key))

    @PostMapping("/{id}/decision")
    @PreAuthorize("@authz.can('inventory.approval.decide')")
    fun legacyDecision(@PathVariable id: UUID, @RequestBody body: String, @RequestHeader("Idempotency-Key") key: String): ResponseEntity<String> {
        val input = WarehouseReceiptJson.decode(body, SafeApprovalDecisionBody::class.java)
        return response(approvals.decide(WarehouseApprovalDecisionInput(id, input.expectedRevision, input.decision, input.reason), key))
    }

    @PostMapping("/rework")
    @PreAuthorize("@authz.can('inventory.approval.request')")
    fun rework(@RequestBody body: String, @RequestHeader("Idempotency-Key") key: String) =
        response(approvals.rework(WarehouseReceiptJson.decode(body, WarehouseApprovalReworkInput::class.java), key))

    private fun response(result: WarehouseApprovalResponse) = ResponseEntity.status(result.status).contentType(MediaType.APPLICATION_JSON).body(result.body)
}

data class SafeApprovalDecisionBody(val expectedRevision: Long, val decision: InventoryApprovalDecision, val reason: String? = null)
