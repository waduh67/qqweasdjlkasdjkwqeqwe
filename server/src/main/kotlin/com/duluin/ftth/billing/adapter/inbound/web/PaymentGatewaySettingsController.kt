package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.PaymentGatewaySettingsView
import com.duluin.ftth.billing.application.port.inbound.UpdatePaymentGatewaySettingsCommand
import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PaymentProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Setelan payment gateway tenant: penyedia (Xendit/Paywuz/Pivot/Manual) + mode (BYO/PLATFORM)
 * + kredensial bawa-sendiri. Kredensial hanya boleh DITULIS (write-only): dikirim saat update,
 * tak pernah dikembalikan — GET hanya menandakan sudah terisi/belum.
 */
@RestController
@RequestMapping("/api/billing/gateway-settings")
@Tag(name = "Billing — setelan payment gateway")
@SecurityRequirement(name = "bearer-jwt")
class PaymentGatewaySettingsController(
    private val useCase: ManagePaymentGatewaySettingsUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('billing.gateway.view')")
    @Operation(summary = "Setelan payment gateway tenant")
    fun get(): PaymentGatewaySettingsView = useCase.get()

    @PutMapping
    @PreAuthorize("@authz.can('billing.gateway.manage')")
    @Operation(summary = "Ubah penyedia, mode, & kredensial payment gateway")
    fun update(@Valid @RequestBody request: PaymentGatewaySettingsRequest): PaymentGatewaySettingsView =
        useCase.update(request.toCommand())
}

/**
 * Kredensial ([apiKey]/[secretKey]/[webhookToken]) opsional: kosong/absen = biarkan yang
 * tersimpan. Batas panjang mengikuti validasi domain agar pesan galat konsisten di klien.
 */
data class PaymentGatewaySettingsRequest(
    @field:NotNull val provider: PaymentProvider,
    @field:NotNull val mode: GatewayMode,
    @field:NotNull val enabled: Boolean,
    @field:Size(max = 512) val apiKey: String? = null,
    @field:Size(max = 512) val secretKey: String? = null,
    @field:Size(max = 512) val webhookToken: String? = null,
) {
    fun toCommand() = UpdatePaymentGatewaySettingsCommand(
        provider = provider,
        mode = mode,
        enabled = enabled,
        apiKey = apiKey,
        secretKey = secretKey,
        webhookToken = webhookToken,
    )
}
