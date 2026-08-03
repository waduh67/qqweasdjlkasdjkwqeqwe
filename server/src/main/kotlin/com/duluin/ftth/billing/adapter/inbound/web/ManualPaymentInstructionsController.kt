package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.ManagePaymentGatewaySettingsUseCase
import com.duluin.ftth.billing.application.port.inbound.ManualPaymentInstructionsView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Instruksi bayar manual (tunai/transfer/QRIS) untuk ditunjukkan ke pelanggan pada tagihan
 * MANUAL. Dipisah dari setelan gateway karena di-gate `billing.invoice.view` (izin yang dimiliki
 * operator halaman detail pelanggan), bukan `billing.gateway.view`. Non-rahasia; byte gambar QRIS
 * diambil lewat `GET /api/billing/gateway-settings/qris` (canAny view invoice/gateway).
 */
@RestController
@RequestMapping("/api/billing/manual-payment-instructions")
@Tag(name = "Billing — instruksi pembayaran manual")
@SecurityRequirement(name = "bearer-jwt")
class ManualPaymentInstructionsController(
    private val useCase: ManagePaymentGatewaySettingsUseCase,
) {
    @GetMapping
    @PreAuthorize("@authz.can('billing.invoice.view')")
    @Operation(summary = "Instruksi bayar manual tenant (untuk tagihan MANUAL di halaman pelanggan)")
    fun get(): ManualPaymentInstructionsView = useCase.manualPaymentInstructions()
}
