package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/counts")
class WarehouseCountController(private val counts: InventoryCountApi) {
    @PostMapping
    fun create(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(counts.create(WarehouseReceiptJson.decode(body, WarehouseCountDraft::class.java), key))
    @PostMapping("/{id}/start")
    fun start(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(counts.start(id, WarehouseReceiptJson.decode(body, WarehouseCountRevision::class.java), key))
    @PostMapping("/{id}/observe")
    fun observe(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(counts.observe(id, WarehouseReceiptJson.decode(body, WarehouseCountObservation::class.java), key))
    @PostMapping("/{id}/submit")
    fun submit(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(counts.submit(id, WarehouseReceiptJson.decode(body, WarehouseCountRevision::class.java), key))
    @PostMapping("/{id}/recount")
    fun recount(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(counts.recount(id, WarehouseReceiptJson.decode(body, WarehouseCountRevision::class.java), key))
    @GetMapping("/{id}") fun get(@PathVariable id: UUID) = counts.get(id)
    @GetMapping("/{id}/history") fun history(@PathVariable id: UUID) = counts.history(id)
    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int) = counts.list(page, size)
    private fun response(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").body(receipt.originalBody)
}
