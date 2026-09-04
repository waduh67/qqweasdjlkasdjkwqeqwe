package com.duluin.ftth.workorder.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.workorder.application.port.inbound.AttachEvidenceCommand
import com.duluin.ftth.workorder.application.port.inbound.CaptureSignatureCommand
import com.duluin.ftth.workorder.application.port.inbound.EvidenceView
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderEvidenceUseCase
import com.duluin.ftth.workorder.application.port.inbound.SignatureView
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderEvidenceQuery
import com.duluin.ftth.workorder.domain.model.EvidenceKind
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Bukti pengerjaan sebuah work order: foto & tanda tangan. Unggah lewat multipart;
 * byte-nya di-proxy balik lewat endpoint content agar akses tetap ter-gate izin
 * `workorder.evidence.view` dan ter-scope tenant, tanpa mengekspos storage ke klien.
 *
 * Unggah biasanya datang dari klien teknisi (dibangun terpisah); operator memakai
 * endpoint yang sama untuk melihat, dan bila perlu melampirkan/mencabut, bukti.
 */
@RestController
@RequestMapping("/api/work-orders/{workOrderId}")
@Tag(name = "Work Order Evidence")
@SecurityRequirement(name = "bearer-jwt")
class WorkOrderEvidenceController(
    private val manage: ManageWorkOrderEvidenceUseCase,
    private val query: WorkOrderEvidenceQuery,
) {
    @GetMapping("/evidence")
    @PreAuthorize("@authz.canAny('workorder.evidence.view','workorder.order.field')")
    fun list(@PathVariable workOrderId: UUID): List<EvidenceView> = query.listPhotos(workOrderId)

    @PostMapping("/evidence", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canAny('workorder.evidence.manage','workorder.order.field')")
    fun upload(
        @PathVariable workOrderId: UUID,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(defaultValue = "OTHER") kind: EvidenceKind,
        @RequestParam(required = false) caption: String?,
        @RequestParam(required = false) latitude: Double?,
        @RequestParam(required = false) longitude: Double?,
        @RequestParam(required = false) capturedAt: String?,
    ): EvidenceView = manage.attachPhoto(
        workOrderId,
        AttachEvidenceCommand(
            kind = kind,
            caption = caption,
            contentType = file.requireContentType(),
            bytes = file.bytes,
            latitude = latitude,
            longitude = longitude,
            capturedAt = parseInstant(capturedAt),
        ),
    )

    @GetMapping("/evidence/{evidenceId}/content")
    @PreAuthorize("@authz.canAny('workorder.evidence.view','workorder.order.field')")
    fun content(@PathVariable workOrderId: UUID, @PathVariable evidenceId: UUID): ResponseEntity<ByteArray> {
        val content = query.downloadPhoto(workOrderId, evidenceId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType)).body(content.bytes)
    }

    @DeleteMapping("/evidence/{evidenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canAny('workorder.evidence.manage','workorder.order.field')")
    fun delete(@PathVariable workOrderId: UUID, @PathVariable evidenceId: UUID) =
        manage.removePhoto(workOrderId, evidenceId)

    @GetMapping("/signature")
    @PreAuthorize("@authz.canAny('workorder.evidence.view','workorder.order.field')")
    fun signature(@PathVariable workOrderId: UUID): ResponseEntity<SignatureView> =
        query.getSignature(workOrderId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @PutMapping("/signature", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("@authz.canAny('workorder.evidence.manage','workorder.order.field')")
    fun sign(
        @PathVariable workOrderId: UUID,
        @RequestParam("file") file: MultipartFile,
        @RequestParam signerName: String,
        @RequestParam(required = false) signedAt: String?,
    ): SignatureView = manage.captureSignature(
        workOrderId,
        CaptureSignatureCommand(
            signerName = signerName,
            contentType = file.requireContentType(),
            bytes = file.bytes,
            signedAt = parseInstant(signedAt),
        ),
    )

    @GetMapping("/signature/content")
    @PreAuthorize("@authz.canAny('workorder.evidence.view','workorder.order.field')")
    fun signatureContent(@PathVariable workOrderId: UUID): ResponseEntity<ByteArray> {
        val content = query.downloadSignature(workOrderId)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType)).body(content.bytes)
    }

    @DeleteMapping("/signature")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canAny('workorder.evidence.manage','workorder.order.field')")
    fun unsign(@PathVariable workOrderId: UUID) = manage.removeSignature(workOrderId)

    private fun MultipartFile.requireContentType(): String =
        contentType ?: throw ValidationException("Tipe konten berkas tidak diketahui")

    private fun parseInstant(raw: String?): Instant? =
        raw?.takeIf { it.isNotBlank() }?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                throw ValidationException("Format waktu '$it' tidak valid (harus ISO-8601)")
            }
        }
}
