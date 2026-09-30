package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/rma-handovers")
class WarehouseRmaHandoverController(private val rma: InventoryCustomerRmaApi) {
    @PostMapping("/{id}/acknowledge")
    fun acknowledge(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> {
        val receipt = rma.acknowledge(id, WarehouseReceiptJson.decode(body, CustomerRmaReceipt::class.java), WarehouseMutationMetadata(key))
        return ResponseEntity.status(receipt.originalStatus).contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }
    @GetMapping("/{id}") fun get(@PathVariable id: UUID): CustomerRmaHandover = rma.get(id)
    @GetMapping("/{id}/details") fun details(@PathVariable id: UUID): CustomerRmaHandoverDetails = rma.details(id)
}
