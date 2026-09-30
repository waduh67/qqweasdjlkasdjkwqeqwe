package com.duluin.ftth.fulfillment

import com.duluin.ftth.fulfillment.application.service.MaterialReworkService
import com.duluin.ftth.inventory.MaterialReworkRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/work-orders/{id}/materials")
class MaterialReworkController(private val rework: MaterialReworkService) {
    @GetMapping("/rework-context")
    fun basis(@PathVariable id: UUID) = rework.basis(id)

    @PostMapping("/rework")
    fun append(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> {
        val receipt = rework.append(id, MaterialWorkflowJson.decode(body, MaterialReworkRequest::class.java), key)
        return ResponseEntity.status(receipt.originalStatus).contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }
}
