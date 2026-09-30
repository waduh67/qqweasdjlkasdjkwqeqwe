package com.duluin.ftth.fulfillment

import com.duluin.ftth.fulfillment.application.service.MaterialUsageService
import com.duluin.ftth.inventory.InventoryMaterialUsageApi
import com.duluin.ftth.inventory.MaterialUsageRequest
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
class MaterialUsageController(private val workflow: MaterialUsageService, private val inventory: InventoryMaterialUsageApi) {
    @PostMapping("/api/work-orders/{id}/materials/report-use")
    fun reportUse(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> {
        val receipt = workflow.reportUse(id, MaterialWorkflowJson.decode(body, MaterialUsageRequest::class.java), key)
        return ResponseEntity.status(receipt.originalStatus).contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }

    @GetMapping("/api/v1/warehouse/my-material-usage/{id}")
    fun usage(@PathVariable id: UUID): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(inventory.usage(id))
}
