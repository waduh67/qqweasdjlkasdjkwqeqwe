package com.duluin.ftth.hotspot.adapter.inbound.web

import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.bng.VoucherSessionRef
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.hotspot.application.port.inbound.GenerateVoucherBatchCommand
import com.duluin.ftth.hotspot.application.port.inbound.GeneratedVoucherBatch
import com.duluin.ftth.hotspot.application.port.inbound.HotspotSessionView
import com.duluin.ftth.hotspot.application.port.inbound.ManageVoucherUseCase
import com.duluin.ftth.hotspot.domain.model.Voucher
import com.duluin.ftth.hotspot.domain.model.VoucherBatch
import com.duluin.ftth.hotspot.domain.model.VoucherStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/api/hotspot")
@Tag(name = "Hotspot & Voucher")
@SecurityRequirement(name = "bearer-jwt")
class VoucherController(
    private val vouchers: ManageVoucherUseCase,
    private val bng: BngApi,
) {
    @PostMapping("/voucher-batches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('hotspot.voucher.manage')")
    fun generate(@Valid @RequestBody request: GenerateVoucherBatchRequest): ResponseEntity<GeneratedVoucherBatchResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(
                vouchers.generateBatch(
                    GenerateVoucherBatchCommand(
                        requireNotNull(request.siteId),
                        requireNotNull(request.planId),
                        Duration.ofSeconds(request.durationSeconds),
                        request.quantity,
                    ),
                ).toResponse(),
            )

    @GetMapping("/voucher-batches")
    @PreAuthorize("@authz.can('hotspot.voucher.view')")
    fun listBatches(
        @RequestParam(required = false) siteId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<VoucherBatchResponse> =
        PageResponse.from(vouchers.listBatches(siteId, PageRequest(page, size, sort = "id", descending = true)).map { it.toResponse() })

    @GetMapping("/voucher-batches/{batchId}")
    @PreAuthorize("@authz.can('hotspot.voucher.view')")
    fun getBatch(@PathVariable batchId: UUID): VoucherBatchResponse = vouchers.getBatch(batchId).toResponse()

    @GetMapping("/vouchers")
    @PreAuthorize("@authz.can('hotspot.voucher.view')")
    fun listVouchers(
        @RequestParam(required = false) batchId: UUID?,
        @RequestParam(required = false) siteId: UUID?,
        @RequestParam(required = false) status: VoucherStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<VoucherResponse> =
        PageResponse.from(vouchers.listVouchers(batchId, siteId, status, PageRequest(page, size, sort = "id", descending = true)).map { it.toResponse() })

    @GetMapping("/vouchers/{voucherId}")
    @PreAuthorize("@authz.can('hotspot.voucher.view')")
    fun getVoucher(@PathVariable voucherId: UUID): VoucherResponse = vouchers.getVoucher(voucherId).toResponse()

    @GetMapping("/vouchers/{externalId}/session")
    @PreAuthorize("@authz.can('hotspot.session.view')")
    fun getSession(@PathVariable externalId: String): VoucherSessionResponse =
        bng.findVoucherSession(externalId)?.toResponse()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Voucher session not found")

    @PostMapping("/vouchers/{voucherId}/revoke")
    @PreAuthorize("@authz.can('hotspot.voucher.manage')")
    fun revoke(@PathVariable voucherId: UUID, @Valid @RequestBody request: RevokeVoucherRequest): VoucherResponse =
        vouchers.revoke(voucherId, request.reason).toResponse()
}

data class GenerateVoucherBatchRequest(
    @field:NotNull val siteId: UUID?,
    @field:NotNull val planId: UUID?,
    @field:Min(1) val durationSeconds: Long,
    @field:Min(1) @field:Max(1_000) val quantity: Int,
)

data class RevokeVoucherRequest(@field:NotBlank val reason: String)

data class GeneratedVoucherBatchResponse(
    val batch: VoucherBatchResponse,
    val credentials: List<IssuedVoucherCredentialResponse>,
)

data class IssuedVoucherCredentialResponse(val voucherId: UUID, val username: String, val password: String)
data class VoucherBatchResponse(val id: UUID, val siteId: UUID, val planId: UUID, val durationSeconds: Long, val status: String)
data class VoucherResponse(
    val id: UUID,
    val batchId: UUID?,
    val username: String,
    val siteId: UUID,
    val planId: UUID,
    val durationSeconds: Long,
    val status: String,
    val activatedAt: java.time.Instant?,
    val expiresAt: java.time.Instant?,
    val revokedAt: java.time.Instant?,
    val revocationReason: String?,
)

data class VoucherSessionResponse(
    val externalId: String,
    val online: Boolean,
    val nasId: UUID?,
    val framedIp: String?,
    val startedAt: java.time.Instant?,
    val lastSeenAt: java.time.Instant?,
    val inputBytes: Long?,
    val outputBytes: Long?,
)

private fun GeneratedVoucherBatch.toResponse() = GeneratedVoucherBatchResponse(
    batch.toResponse(),
    credentials.map { IssuedVoucherCredentialResponse(it.voucher.id, it.voucher.username, it.password) },
)
private fun VoucherBatch.toResponse() = VoucherBatchResponse(id, siteId, planId, duration.seconds, status.name)
private fun Voucher.toResponse() = VoucherResponse(id, batchId, username, siteId, planId, duration.seconds, status.name, activatedAt, expiresAt, revokedAt, revocationReason)
private fun VoucherSessionRef.toResponse() =
    VoucherSessionResponse(externalId, online, nasId, framedIp, startedAt, lastSeenAt, inputBytes, outputBytes)
