package com.duluin.ftth.fulfillment

import com.duluin.ftth.fulfillment.application.service.MaterialReceiptService
import com.duluin.ftth.inventory.InventoryMaterialReceiptApi
import com.duluin.ftth.inventory.MaterialReceiptRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class MaterialReceiptController(private val workflow: MaterialReceiptService, private val inventory: InventoryMaterialReceiptApi) {
    @PostMapping("/api/work-orders/{id}/materials/acknowledge")
    fun acknowledge(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> {
        val receipt = workflow.acknowledge(id, MaterialWorkflowJson.decode(body, MaterialReceiptRequest::class.java), key)
        return ResponseEntity.status(receipt.originalStatus).contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }

    @GetMapping("/api/v1/warehouse/my-material-receipts/{id}")
    fun receipt(@PathVariable id: UUID): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(inventory.receipt(id))
}
