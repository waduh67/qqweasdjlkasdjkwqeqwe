package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/settings")
class WarehousePolicyController(private val policies: WarehousePolicyService, private val scopes: WarehouseScopeSettingsService,
    private val delegations: WarehouseDelegationService, private val evaluation: WarehouseEvaluationQuery) {
    @GetMapping("/policy") fun current(): WarehousePolicySettings {
        val current = policies.current()
        return WarehousePolicySettings(current != null, current)
    }
    @GetMapping("/policy/history") fun history(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int) = policies.history(page, size)
    @PutMapping("/policy") fun replace(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        json(policies.replace(WarehouseReceiptJson.decode(body, WarehousePolicyInput::class.java), key))
    @GetMapping("/scopes/{userId}") fun scopes(@PathVariable userId: UUID) = scopes.list(userId)
    @PutMapping("/scopes/{userId}/{locationId}") fun scope(@PathVariable userId: UUID, @PathVariable locationId: UUID,
        @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        json(scopes.replace(userId, locationId, WarehouseReceiptJson.decode(body, WarehouseScopeInput::class.java), key))
    @GetMapping("/delegations") fun delegations() = delegations.list()
    @PostMapping("/delegations") fun delegate(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        json(delegations.create(WarehouseReceiptJson.decode(body, WarehouseDelegationInput::class.java), key))
    @PostMapping("/delegations/{id}/revoke") fun revoke(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        json(delegations.revoke(id, WarehouseReceiptJson.decode(body, WarehouseRevisionInput::class.java), key))
    @PostMapping("/evaluate") fun evaluate(@RequestBody body: String): ResponseEntity<com.duluin.ftth.inventory.application.port.inbound.WarehouseEvaluationView> {
        val result = evaluation.evaluate(WarehouseReceiptJson.decode(body, WarehouseSourceInput::class.java))
        return ResponseEntity.status(result.status).body(result.body)
    }
    private fun json(body: String) = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body)
}
