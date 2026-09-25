package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.service.WarehouseMigrationFinalizationService
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationFinalizeInput
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationBeginInput
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationEvidenceInput
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationResolutionInput
import com.duluin.ftth.inventory.application.port.inbound.WarehouseMigrationOpeningInput
import com.duluin.ftth.inventory.application.service.MigrationEvidenceService
import com.duluin.ftth.inventory.application.service.MigrationResolutionService
import com.duluin.ftth.inventory.application.service.WarehouseProvenanceMigrationService
import com.duluin.ftth.inventory.application.service.WarehouseOpeningBalanceService
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/provenance")
class WarehouseProvenanceController(private val service: WarehouseProvenanceMigrationService, private val evidence: MigrationEvidenceService,
    private val resolutions: MigrationResolutionService, private val opening: WarehouseOpeningBalanceService,
    private val finalization: WarehouseMigrationFinalizationService) {
    @GetMapping("/batches/{batch}/finalization")
    fun finalization(@PathVariable batch: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return response(finalization.review(batch))
    }
    @PostMapping("/batches/{batch}/finalization")
    fun finalize(@PathVariable batch: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: String, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return response(finalization.finalize(batch, WarehouseReceiptJson.decode(body, WarehouseMigrationFinalizeInput::class.java), key))
    }
    @GetMapping("/batches/{batch}/review")
    fun review(@PathVariable batch: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<*> {
        if (parameters.isNotEmpty()) invalid()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(opening.review(batch))
    }
    @PostMapping("/batches/{batch}/opening")
    fun opening(@PathVariable batch: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: String, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(
            opening.request(batch, WarehouseReceiptJson.decode(body, WarehouseMigrationOpeningInput::class.java), key))
    }
    @GetMapping("/batches/{batch}/opening/{id}")
    fun opening(@PathVariable batch: UUID, @PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return response(opening.get(batch, id))
    }
    @GetMapping("/batches/{batch}/opening")
    fun openings(@PathVariable batch: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, fallback: Int) = parameters[key]?.single()?.toIntOrNull() ?: if (key in parameters) invalid() else fallback
        return response(opening.list(batch, number("page", 0), number("size", 25)))
    }
    @PostMapping("/batches/{batch}/cases/{case}/resolutions")
    fun resolve(@PathVariable batch: UUID, @PathVariable case: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: String, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        if (parameters.isNotEmpty()) invalid()
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(
            resolutions.resolve(batch, case, WarehouseReceiptJson.decode(body, WarehouseMigrationResolutionInput::class.java), key))
    }
    @GetMapping("/batches/{batch}/cases/{case}/resolutions")
    fun resolutions(@PathVariable batch: UUID, @PathVariable case: UUID,
        @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<*> {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, fallback: Int) = parameters[key]?.single()?.toIntOrNull() ?: if (key in parameters) invalid() else fallback
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resolutions.history(batch, case, number("page", 0), number("size", 25)))
    }
    @PostMapping("/batches/{batch}/cases/{case}/evidence", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable batch: UUID, @PathVariable case: UUID, @RequestHeader("Idempotency-Key") key: String,
        @RequestParam("request") body: String, @RequestParam file: MultipartFile, request: MultipartHttpServletRequest): ResponseEntity<String> {
        if (request.parameterMap.keys != setOf("request") || request.parameterMap.values.any { it.size != 1 } ||
            request.multiFileMap.keys != setOf("file") || request.multiFileMap["file"]?.size != 1 || file.size > 15728640) invalid()
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(
            evidence.upload(batch, case, WarehouseReceiptJson.decode(body, WarehouseMigrationEvidenceInput::class.java), key,
                file.contentType ?: "", file.bytes))
    }
    @GetMapping("/batches/{batch}/cases/{case}/evidence")
    fun evidence(@PathVariable batch: UUID, @PathVariable case: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<*> {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, fallback: Int) = parameters[key]?.single()?.toIntOrNull() ?: if (key in parameters) invalid() else fallback
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence.list(batch, case, number("page", 0), number("size", 25)))
    }
    @GetMapping("/batches/{batch}/cases/{case}/evidence/{id}")
    fun download(@PathVariable batch: UUID, @PathVariable case: UUID, @PathVariable id: UUID,
        @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<ByteArray> {
        if (parameters.isNotEmpty()) invalid()
        val file = evidence.download(batch, case, id)
        val extension = when (file.contentType) { "application/pdf" -> "pdf"; "image/png" -> "png"; else -> "jpg" }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.contentType))
            .header("Content-Disposition", "attachment; filename=\"bukti-migrasi-$id.$extension\"")
            .header("X-Content-Type-Options", "nosniff").contentLength(file.size).body(file.bytes)
    }
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
