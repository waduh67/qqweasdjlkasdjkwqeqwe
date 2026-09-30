package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/asset-losses")
class WarehouseAssetLossController(private val losses: InventoryAssetLossApi) {
    @PostMapping
    fun request(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<WarehouseAssetLossView> =
        ResponseEntity.status(201).body(losses.request(WarehouseReceiptJson.decode(body, WarehouseAssetLossInput::class.java), WarehouseMutationMetadata(key)))

    @GetMapping("/{id}") fun get(@PathVariable id: UUID): WarehouseAssetLossView = losses.get(id)

    @GetMapping fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int): WarehousePage<WarehouseAssetLossView> =
        losses.list(page, size)
}
