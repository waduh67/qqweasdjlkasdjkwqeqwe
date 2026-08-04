package com.duluin.ftth.iam.adapter.inbound.web

import com.duluin.ftth.iam.application.port.inbound.OnboardTenantCommand
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantResult
import com.duluin.ftth.iam.application.port.inbound.OnboardTenantUseCase
import com.duluin.ftth.tenancy.TenantStatus
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Onboarding tenant baru oleh platform admin: membuat tenant + role "Tenant Admin"
 * + user admin awal. Berada di module iam karena melibatkan pembuatan user.
 */
@RestController
@RequestMapping("/api/platform/tenants")
@Tag(name = "Platform — Tenants")
@SecurityRequirement(name = "bearer-jwt")
class TenantOnboardingController(
    private val onboarding: OnboardTenantUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('platform.tenant.create')")
    fun onboard(@Valid @RequestBody request: OnboardTenantRequest): OnboardTenantResponse =
        OnboardTenantResponse.from(
            onboarding.onboard(
                OnboardTenantCommand(
                    slug = request.slug,
                    name = request.name,
                    adminEmail = request.adminEmail,
                    adminName = request.adminName,
                    adminPassword = request.adminPassword,
                    monthlyFee = request.monthlyFee,
                ),
            ),
        )
}

data class OnboardTenantRequest(
    @field:NotBlank val slug: String,
    @field:NotBlank val name: String,
    @field:Email @field:NotBlank val adminEmail: String,
    @field:NotBlank val adminName: String,
    @field:Size(min = 8) val adminPassword: String,
    /** Harga langganan khusus (opsional); null/kosong = pakai harga default global. */
    @field:DecimalMin(value = "0", message = "Harga bulanan tidak boleh negatif")
    val monthlyFee: BigDecimal? = null,
)

data class OnboardTenantResponse(
    val id: UUID,
    val slug: String,
    val name: String,
    val status: TenantStatus,
    val adminUserCreated: Boolean,
) {
    companion object {
        fun from(result: OnboardTenantResult) = OnboardTenantResponse(
            id = result.tenant.id,
            slug = result.tenant.slug,
            name = result.tenant.name,
            status = result.tenant.status,
            adminUserCreated = result.adminUserCreated,
        )
    }
}
