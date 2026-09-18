package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.WarehouseTransferService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/transfers")
class WarehouseTransferController(private val transfers: WarehouseTransferService) {
    @PostMapping
    fun create(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(transfers.create(WarehouseReceiptJson.decode(body, WarehouseTransferDraft::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/dispatch")
    fun dispatch(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(transfers.dispatch(id, WarehouseReceiptJson.decode(body, WarehouseTransferRevision::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/receive")
    fun receive(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        response(transfers.receive(id, WarehouseReceiptJson.decode(body, WarehouseTransferReceipt::class.java), WarehouseMutationMetadata(key)))

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> =
        transfers.cancel(id, WarehouseReceiptJson.decode(body, WarehouseTransferRevision::class.java))

    @GetMapping("/{id}") fun get(@PathVariable id: UUID): WarehouseTransferView = transfers.get(id)
    @GetMapping("/{id}/history") fun history(@PathVariable id: UUID): List<WarehouseTransferView> = transfers.history(id)

    private fun response(receipt: WarehouseOperationReceipt): ResponseEntity<String> = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
