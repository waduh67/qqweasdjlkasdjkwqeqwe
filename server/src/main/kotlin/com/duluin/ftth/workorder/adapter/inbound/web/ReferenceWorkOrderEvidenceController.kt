package com.duluin.ftth.workorder.adapter.inbound.web

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.workorder.application.service.ReferenceWorkOrderEvidenceService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v2/work-orders/{id}/evidence")
class ReferenceWorkOrderEvidenceController(private val service: ReferenceWorkOrderEvidenceService) {
    @GetMapping fun list(@PathVariable id: UUID) = ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(id))
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestParam expectedRevision: Long,
        @RequestParam slot: String, @RequestParam file: MultipartFile): ResponseEntity<String> {
        if (file.size !in 1..5L * 1024 * 1024) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val receipt = service.upload(id, expectedRevision, slot, file.contentType ?: "", file.bytes, key)
        return ResponseEntity.status(receipt.originalStatus).header("Cache-Control", "no-store")
            .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
    }
    @GetMapping("/{photoId}/content") fun content(@PathVariable id: UUID, @PathVariable photoId: UUID): ResponseEntity<ByteArray> {
        val content = service.download(id, photoId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType)).header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff").header("Content-Disposition", "inline; filename=evidence").body(content.bytes)
    }
}
