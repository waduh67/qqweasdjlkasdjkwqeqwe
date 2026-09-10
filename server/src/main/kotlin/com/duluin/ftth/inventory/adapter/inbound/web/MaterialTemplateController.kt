package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/warehouse/material-templates/{workType}/{action}")
class MaterialTemplateController(private val templates: InventoryMaterialTemplateApi) {
    @GetMapping
    fun current(@PathVariable workType: String, @PathVariable action: String) = templates.current(workType, action)
        ?: throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "No material template configured"))
    @PutMapping
    fun publish(@PathVariable workType: String, @PathVariable action: String, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: String): ResponseEntity<String> {
        val receipt = templates.publish(workType, action, WarehouseReceiptJson.decode(body, MaterialTemplateRequest::class.java), WarehouseMutationMetadata(key))
        return ResponseEntity.status(receipt.originalStatus).contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }
}
