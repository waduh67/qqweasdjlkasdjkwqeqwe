package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/dispositions")
class WarehouseDispositionController(private val dispositions: InventoryDispositionApi) {
    @PostMapping
    fun request(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<WarehouseDispositionView> =
        ResponseEntity.status(201).body(dispositions.request(WarehouseReceiptJson.decode(body, WarehouseDispositionInput::class.java), WarehouseMutationMetadata(key)))

    @GetMapping("/{id}") fun get(@PathVariable id: UUID): WarehouseDispositionView = dispositions.get(id)

    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int,
        @RequestParam(required = false) sourceDocumentId: UUID?, @RequestParam(required = false) action: WarehouseDispositionAction?): WarehousePage<WarehouseDispositionView> =
        dispositions.list(WarehouseDispositionFilter(page, size, sourceDocumentId, action))
}
