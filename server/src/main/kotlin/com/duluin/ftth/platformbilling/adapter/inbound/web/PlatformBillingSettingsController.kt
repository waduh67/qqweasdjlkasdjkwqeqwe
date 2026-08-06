package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.platformbilling.application.port.inbound.ManagePlatformBillingSettingsUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.PlatformBillingSettingsView
import com.duluin.ftth.platformbilling.application.port.inbound.UpdatePlatformSettingsCommand
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.math.BigDecimal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Setelan billing SaaS untuk super-admin platform: default grace/jatuh-tempo/tanggal-tagih + harga
 * bulanan bawaan langganan tenant. Dijaga izin `platform.billing.*` (platform admin otomatis lolos).
 * Kredensial pembayaran (akun master Pivot) dikelola di endpoint terpisah `/api/platform/pivot-config`.
 */
@RestController
@RequestMapping("/api/platform/billing/settings")
@Tag(name = "Platform — Billing Settings")
@SecurityRequirement(name = "bearer-jwt")
class PlatformBillingSettingsController(
    private val useCase: ManagePlatformBillingSettingsUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.billing.view')")
    fun get(): PlatformBillingSettingsView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('platform.billing.manage')")
    fun updateSetting(@Valid @RequestBody body: UpdateSettingRequest): PlatformBillingSettingsView =
        useCase.updateSetting(
            UpdatePlatformSettingsCommand(
                defaultGraceDays = body.defaultGraceDays,
                defaultDueDays = body.defaultDueDays,
                defaultBillingDay = body.defaultBillingDay,
                defaultMonthlyFee = body.defaultMonthlyFee,
                currency = body.currency,
            ),
        )
}

data class UpdateSettingRequest(
    @field:Min(0) @field:Max(90)
    val defaultGraceDays: Int = 7,
    @field:Min(0) @field:Max(90)
    val defaultDueDays: Int = 7,
    @field:Min(1) @field:Max(28)
    val defaultBillingDay: Int = 1,
    @field:DecimalMin(value = "0", message = "Harga bulanan default tidak boleh negatif")
    val defaultMonthlyFee: BigDecimal = BigDecimal.ZERO,
    val currency: String = "IDR",
)
