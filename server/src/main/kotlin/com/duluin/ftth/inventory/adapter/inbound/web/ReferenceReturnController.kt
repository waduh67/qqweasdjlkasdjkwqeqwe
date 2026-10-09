package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.ReferenceReturnService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v2/warehouse/returns")
class ReferenceReturnController(private val service: ReferenceReturnService) {
    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) state: ReferenceReturnState?, @RequestParam(required = false) search: String?) = fresh(service.list(page, size, state, search))
    @GetMapping("/{id}") fun detail(@PathVariable id: UUID) = fresh(service.detail(id))
    @PostMapping fun submit(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.submit(WarehouseReceiptJson.decode(body, ReferenceReturnInput::class.java), key))
    @PostMapping("/{id}/decision") fun decide(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        result(service.decide(id, WarehouseReceiptJson.decode(body, ReferenceReturnDecision::class.java), key))
    private fun <T> fresh(body: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(body)
    private fun result(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
