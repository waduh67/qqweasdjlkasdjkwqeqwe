package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.platformbilling.application.port.inbound.ManagePlatformBillingSettingsUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.PlatformBillingSettingsView
import com.duluin.ftth.platformbilling.application.port.inbound.UpdatePlatformGatewayCommand
import com.duluin.ftth.platformbilling.application.port.inbound.UpdatePlatformSettingsCommand
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Setelan billing SaaS untuk super-admin platform: pilih gateway aktif menagih langganan tenant
 * + kredensial tiap penyedia. Dijaga izin `platform.billing.*` (platform admin otomatis lolos).
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
                activeProvider = body.activeProvider!!,
                defaultGraceDays = body.defaultGraceDays,
                defaultDueDays = body.defaultDueDays,
                defaultBillingDay = body.defaultBillingDay,
                currency = body.currency,
            ),
        )

    @PutMapping("/gateways/{provider}")
    @PreAuthorize("@authz.can('platform.billing.manage')")
    fun updateGateway(
        @PathVariable provider: PlatformPaymentProvider,
        @Valid @RequestBody body: UpdateGatewayRequest,
    ): PlatformBillingSettingsView =
        useCase.updateGateway(
            UpdatePlatformGatewayCommand(
                provider = provider,
                enabled = body.enabled,
                apiKey = body.apiKey,
                secretKey = body.secretKey,
                webhookToken = body.webhookToken,
                paymentMethod = body.paymentMethod,
            ),
        )
}

data class UpdateSettingRequest(
    @field:NotNull(message = "Gateway aktif wajib dipilih")
    val activeProvider: PlatformPaymentProvider?,
    @field:Min(0) @field:Max(90)
    val defaultGraceDays: Int = 7,
    @field:Min(0) @field:Max(90)
    val defaultDueDays: Int = 7,
    @field:Min(1) @field:Max(28)
    val defaultBillingDay: Int = 1,
    val currency: String = "IDR",
)

/** Rahasia null/kosong = biarkan apa adanya (tak menghapus kredensial saat menyunting). */
data class UpdateGatewayRequest(
    val enabled: Boolean = false,
    val apiKey: String? = null,
    val secretKey: String? = null,
    val webhookToken: String? = null,
    val paymentMethod: String? = null,
)
