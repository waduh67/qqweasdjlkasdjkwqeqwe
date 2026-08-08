package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
import com.duluin.ftth.platformbilling.application.port.inbound.SimulatePaymentCommand
import com.duluin.ftth.platformbilling.application.port.inbound.SimulatePaymentResult
import com.duluin.ftth.platformbilling.application.port.inbound.SimulatePaymentUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.SimulationAvailability
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Panel simulasi pembayaran super-admin (alat uji sandbox): paksa sebuah sesi bayar penyedia menjadi
 * lunas/kedaluwarsa tanpa transaksi sungguhan, agar alur webhook → pelunasan bisa diuji ujung-ke-ujung.
 * Lintas-tenant (akun master) → dijaga izin `platform.billing.manage`.
 */
@RestController
@RequestMapping("/api/platform/payments")
@Tag(name = "Platform — simulasi pembayaran")
@SecurityRequirement(name = "bearer-jwt")
class PlatformPaymentSimulationController(
    private val useCase: SimulatePaymentUseCase,
) {
    @GetMapping("/simulate")
    @PreAuthorize("@authz.can('platform.billing.view')")
    @Operation(summary = "Ketersediaan simulasi (butuh Pivot master aktif & mode sandbox)")
    fun availability(): SimulationAvailability = useCase.availability()

    @PostMapping("/simulate")
    @PreAuthorize("@authz.can('platform.billing.manage')")
    @Operation(summary = "Kirim simulasi pembayaran untuk sebuah payment session ID")
    fun simulate(@RequestBody request: SimulatePaymentBody): SimulatePaymentResult =
        useCase.simulate(SimulatePaymentCommand(request.paymentSessionId, request.status, request.subMerchantId))
}

/**
 * [paymentSessionId] = id sesi bayar Pivot (`data.id` saat create payment; sama dengan `gatewayRef`
 * yang tersimpan di tagihan). [subMerchantId] hanya untuk sesi yang dibuat atas nama sub-account tenant.
 */
data class SimulatePaymentBody(
    val paymentSessionId: String,
    val status: SimulatedChargeStatus,
    val subMerchantId: String? = null,
)
