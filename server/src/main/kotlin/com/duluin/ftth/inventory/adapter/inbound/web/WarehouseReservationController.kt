package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/material-requests")
class WarehouseReservationController(private val reservations: InventoryReservationApi) {
    @PostMapping("/{id}/{action}")
    fun execute(@PathVariable id: UUID, @PathVariable action: String, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> {
        val transition = ReservationAction.entries.singleOrNull { it.name.lowercase() == action } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val receipt = reservations.execute(id, transition, WarehouseReceiptJson.decode(body, ReservationRequest::class.java), WarehouseMutationMetadata(key))
        return ResponseEntity.status(receipt.originalStatus).contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }
    @GetMapping("/allocations/{workOrderId}")
    fun allocations(@PathVariable workOrderId: UUID) = reservations.allocations(workOrderId)
}
