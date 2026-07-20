package com.duluin.ftth.tenancy.adapter.inbound.web

import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.web.PageResponse
import com.duluin.ftth.tenancy.TenantRef
import com.duluin.ftth.tenancy.TenantStatus
import com.duluin.ftth.tenancy.application.port.inbound.ManageTenantUseCase
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
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
) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.tenant.view')")
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<TenantResponse> =
        PageResponse.from(
            manageTenant.list(PageRequest(page, size, sort = "createdAt", descending = true))
                .map(TenantResponse::from),
        )

    @GetMapping("/{id}")
    @PreAuthorize("@authz.can('platform.tenant.view')")
    fun get(@PathVariable id: UUID): TenantResponse =
        TenantResponse.from(manageTenant.get(id))

    @PostMapping("/{id}/suspend")
    @PreAuthorize("@authz.can('platform.tenant.manage')")
    fun suspend(@PathVariable id: UUID): TenantResponse =
        TenantResponse.from(manageTenant.suspend(id))

    @PostMapping("/{id}/activate")
    @PreAuthorize("@authz.can('platform.tenant.manage')")
    fun activate(@PathVariable id: UUID): TenantResponse =
        TenantResponse.from(manageTenant.activate(id))
}

data class TenantResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: TenantStatus,
) {
    companion object {
        fun from(ref: TenantRef) = TenantResponse(ref.id, ref.slug, ref.name, ref.status)
    }
}
