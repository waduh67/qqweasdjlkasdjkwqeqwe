package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationBeginInput
import com.duluin.ftth.inventory.application.service.WarehouseProvenanceMigrationService
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/provenance")
class WarehouseProvenanceController(private val service: WarehouseProvenanceMigrationService) {
    @PostMapping("/batches") fun begin(@RequestHeader("Idempotency-Key") key: String, @RequestBody body: String,
        @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
            .body(service.begin(WarehouseReceiptJson.decode(body, WarehouseMigrationBeginInput::class.java), key))
    }
    @GetMapping fun summary(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return response(service.summary())
    }
    @GetMapping("/cases") fun cases(@RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.any { (key, values) -> key !in setOf("page", "size", "sourceTable") || values.size != 1 ||
            values.single().isBlank() || values.single().length > 64 }) invalid()
        fun number(key: String, fallback: Int): Int = parameters[key]?.single()?.let {
            if (!it.matches(Regex("[0-9]+"))) invalid()
            it.toIntOrNull() ?: invalid()
        } ?: fallback
        return response(service.cases(number("page", 0), number("size", 25), parameters["sourceTable"]?.single()))
    }
    @GetMapping("/cases/{id}") fun case(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return response(service.cases(0, 1, null, id))
    }
    private fun response(value: String) = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(value)
    private fun invalid(): Nothing = masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
