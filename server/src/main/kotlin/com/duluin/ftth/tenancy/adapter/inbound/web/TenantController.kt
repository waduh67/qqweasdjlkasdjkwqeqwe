package com.duluin.ftth.tenancy.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import com.duluin.ftth.tenancy.application.port.inbound.ManageTenantUseCase
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import com.duluin.ftth.iam.TenantOwnerApi
import com.duluin.ftth.iam.TenantOwnerRef
import com.duluin.ftth.iam.OwnerPasswordReset
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * Pengelolaan tenant untuk platform admin. Dijaga izin `platform.tenant.*`
 * (platform admin otomatis lolos). Onboarding (buat tenant + admin awal) ada di
 * module iam karena melibatkan pembuatan user.
 */
@RestController
@RequestMapping("/api/platform/tenants")
@Tag(name = "Platform — Tenants")
@SecurityRequirement(name = "bearer-jwt")
class TenantController(
    private val manageTenant: ManageTenantUseCase,
    private val owners: TenantOwnerApi,
) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.tenant.view')")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<TenantResponse> {
        val tenants = manageTenant.list(PageRequest(page, size, sort = "createdAt", descending = true))
        val ownerMap = owners.findOwners(tenants.content.map { it.id }.toSet())
        return PageResponse.from(tenants.map { TenantResponse.from(it, ownerMap[it.id]) })
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('platform.tenant.view')")
    fun get(@PathVariable id: UUID): TenantResponse =
        response(manageTenant.get(id))

    @PostMapping("/{id}/suspend")
    @PreAuthorize("@authz.can('platform.tenant.manage')")
    fun suspend(@PathVariable id: UUID): TenantResponse =
        response(manageTenant.suspend(id))

    @PostMapping("/{id}/activate")
    @PreAuthorize("@authz.can('platform.tenant.manage')")
    fun activate(@PathVariable id: UUID): TenantResponse =
        response(manageTenant.activate(id))

    @GetMapping("/{id}/owner/candidates")
    @PreAuthorize("@authz.can('platform.tenant.manage')")
    fun ownerCandidates(@PathVariable id: UUID, @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): PageResponse<TenantOwnerRef> =
        PageResponse.from(owners.candidates(id, query, PageRequest(page, size)))

    @PutMapping("/{id}/owner")
    @PreAuthorize("@authz.can('platform.tenant.manage')")
    fun bindOwner(@PathVariable id: UUID, @RequestBody request: BindTenantOwnerRequest): TenantOwnerRef = owners.bind(id, request.userId)

    @PostMapping("/{id}/owner/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('platform.tenant.manage')")
    fun resetOwnerPassword(@PathVariable id: UUID, @Valid @RequestBody request: ResetTenantOwnerPasswordRequest) =
        owners.resetPassword(id, OwnerPasswordReset(request.expectedOwnerUserId, request.newPassword))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('platform.tenant.delete')")
    fun delete(@PathVariable id: UUID) = manageTenant.delete(id)

    private fun response(ref: TenantRef) = TenantResponse.from(ref, owners.findOwners(setOf(ref.id))[ref.id])
}

data class BindTenantOwnerRequest(val userId: UUID)
data class ResetTenantOwnerPasswordRequest(val expectedOwnerUserId: UUID, @field:NotBlank val newPassword: String)

data class TenantResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: TenantStatus,
    val owner: TenantOwnerRef? = null,
) {
    companion object {
        fun from(ref: TenantRef, owner: TenantOwnerRef? = null) = TenantResponse(ref.id, ref.slug, ref.name, ref.status, owner)
    }
}
