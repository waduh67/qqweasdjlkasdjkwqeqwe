package com.duluin.ftth.inventory.adapter.inbound.web

import com.duluin.ftth.inventory.*
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/warehouse/approvals")
class WarehouseApprovalQueryController(private val queries: InventoryApprovalQueryApi) {
    @GetMapping("/workbench")
    fun list(@RequestParam parameters: MultiValueMap<String, String>) = privateResponse(queries.list(WarehouseApprovalFilters.parse(parameters)))
    @GetMapping("/sources/{id}") fun source(@PathVariable id: UUID) = privateResponse(queries.source(id))
    @GetMapping("/{id}/details") fun details(@PathVariable id: UUID) = privateResponse(queries.details(id))
    @GetMapping("/{id}/attachments")
    fun attachments(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehouseApprovalAttachment>> {
        val filter = WarehouseApprovalFilters.parse(parameters, history = true)
        return privateResponse(queries.attachments(id, WarehousePageRequest(filter.page, filter.size)))
    }
    @GetMapping("/{id}/attachments/{evidenceId}")
    fun attachment(@PathVariable id: UUID, @PathVariable evidenceId: UUID): ResponseEntity<ByteArray> {
        val file = queries.attachment(id, evidenceId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType)).contentLength(file.size)
            .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
            .header("Content-Disposition", "attachment; filename=\"evidence-$evidenceId\"").body(file.bytes)
    }
    @GetMapping("/{id}/history/page")
    fun history(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<WarehousePage<WarehouseApprovalHistoryEntry>> {
        val filter = WarehouseApprovalFilters.parse(parameters, history = true)
        return privateResponse(queries.history(id, WarehousePageRequest(filter.page, filter.size)))
    }
    private fun <T : Any> privateResponse(value: T): ResponseEntity<T> = ResponseEntity.ok().header("Cache-Control", "no-store").body(value)
}
