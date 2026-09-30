package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RequestMapping("/api/v1/warehouse/dispositions/{dispositionId}/compensations")
@RestController
class WarehouseCompensationController(private val compensations: InventoryCompensationApi) {
    @PostMapping
    fun request(@PathVariable dispositionId: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: String): ResponseEntity<WarehouseCompensationView> = ResponseEntity.status(201).body(
        compensations.request(dispositionId, WarehouseReceiptJson.decode(body, WarehouseCompensationInput::class.java), WarehouseMutationMetadata(key)))

    @GetMapping("/{id}") fun get(@PathVariable dispositionId: UUID, @PathVariable id: UUID) = compensations.get(dispositionId, id)

    @GetMapping fun list(@PathVariable dispositionId: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int) = compensations.list(dispositionId, WarehousePageRequest(page, size))
}
