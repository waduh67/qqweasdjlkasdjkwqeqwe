package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/replenishments")
class WarehouseReplenishmentController(private val service: WarehouseReplenishmentApi) {
    @PostMapping("/rules")
    fun create(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(service.saveRule(null, WarehouseReceiptJson.decode(body, ReplenishmentRuleInput::class.java), key))

    @PutMapping("/rules/{id}")
    fun update(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(service.saveRule(id, WarehouseReceiptJson.decode(body, ReplenishmentRuleInput::class.java), key))

    @PostMapping("/rules/{id}/archive")
    fun archive(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(service.archiveRule(id, WarehouseReceiptJson.decode(body, ReplenishmentRevision::class.java), key))

    @PostMapping("/rules/{id}/recompute")
    fun recompute(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(service.recompute(id, WarehouseReceiptJson.decode(body, ReplenishmentRevision::class.java), key))

    @PostMapping("/requests/{id}/accept")
    fun accept(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(service.accept(id, WarehouseReceiptJson.decode(body, ReplenishmentAcceptance::class.java), key))

    @PostMapping("/requests/{id}/cancel")
    fun cancel(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(service.cancel(id, WarehouseReceiptJson.decode(body, ReplenishmentRevision::class.java), key))

    @PostMapping("/requests/{id}/receiving-reference")
    fun receiving(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(service.bindReceiving(id, WarehouseReceiptJson.decode(body, ReplenishmentReceivingReference::class.java), key))

    @GetMapping("/rules/{id}") fun rule(@PathVariable id: UUID) = service.rule(id)
    @GetMapping("/requests/{id}") fun request(@PathVariable id: UUID) = service.request(id)
    @GetMapping("/rules")
    fun rules(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) locationId: UUID?, @RequestParam(required = false) skuId: UUID?) = service.rules(page, size, locationId, skuId)
    @GetMapping("/requests")
    fun requests(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) locationId: UUID?, @RequestParam(required = false) skuId: UUID?) = service.requests(page, size, locationId, skuId)
    @GetMapping("/rules/{id}/history")
    fun history(@PathVariable id: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int) = service.history(id, page, size)

    private fun response(result: ReplenishmentCommandResult) = ResponseEntity.status(result.status)
        .contentType(MediaType.APPLICATION_JSON).body(result.body)
}
