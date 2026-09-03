package com.duluin.ftth.provisioning.adapter.inbound.web

import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningCertificationUseCase
import com.duluin.ftth.provisioning.application.service.CertifyAdapterCommand
import com.duluin.ftth.provisioning.domain.model.AdapterCertification
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/provisioning/certifications")
@Tag(name = "Platform - Sertifikasi provisioning")
@SecurityRequirement(name = "bearer-jwt")
class ProvisioningCertificationController(
    private val certifications: ProvisioningCertificationUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.isPlatformAdmin()")
    fun list(@PathVariable tenantId: UUID) = certifications.list(tenantId).map(AdapterCertification::toResponse)

    @PostMapping
    @PreAuthorize("@authz.isPlatformAdmin()")
    fun certify(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CertifyAdapterRequest,
    ): ResponseEntity<AdapterCertificationResponse> = ResponseEntity.status(HttpStatus.CREATED).body(
        certifications.certify(request.toCommand(tenantId)).toResponse(),
    )

    @PostMapping("/{certificationId}/revoke")
    @PreAuthorize("@authz.isPlatformAdmin()")
    fun revoke(
        @PathVariable tenantId: UUID,
        @PathVariable certificationId: UUID,
        @RequestHeader("If-Match") revision: String,
    ): AdapterCertificationResponse = certifications.revoke(tenantId, certificationId, parseRevision(revision)).toResponse()
}

data class CertifyAdapterRequest(
    val deviceKind: DeviceKind,
    val deviceId: UUID,
    @field:NotBlank @field:Size(max = 120) val vendor: String,
    @field:NotBlank @field:Size(max = 120) val model: String,
    @field:NotBlank @field:Size(max = 120) val firmware: String,
    @field:NotBlank @field:Size(max = 120) val transport: String,
    @field:NotBlank @field:Size(max = 120) val operationClass: String,
    val validUntil: Instant,
) {
    fun toCommand(tenantId: UUID) = CertifyAdapterCommand(
        tenantId,
        DeviceReference(deviceKind, deviceId),
        vendor,
        model,
        firmware,
        transport,
        operationClass,
        validUntil,
    )
}

data class AdapterCertificationResponse(
    val id: UUID,
    val tenantId: UUID,
    val deviceKind: DeviceKind,
    val deviceId: UUID,
    val vendor: String,
    val model: String,
    val firmware: String,
    val transport: String,
    val operationClass: String,
    val status: String,
    val validUntil: Instant,
    val evidenceId: UUID?,
    val revokedAt: Instant?,
    val revision: Int,
)

private fun AdapterCertification.toResponse() = AdapterCertificationResponse(
    id,
    tenantId,
    device.kind,
    device.id,
    vendor,
    model,
    firmware,
    transport,
    operationClass,
    status.name,
    validUntil,
    evidenceId,
    revokedAt,
    if (active) 1 else 2,
)
