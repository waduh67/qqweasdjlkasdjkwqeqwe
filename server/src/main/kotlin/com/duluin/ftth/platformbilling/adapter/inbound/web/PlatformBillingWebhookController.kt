package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.platformbilling.application.service.PlatformGatewayResolver
import com.duluin.ftth.platformbilling.application.service.PlatformPaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Webhook pelunasan langganan tenant — TANPA autentikasi bearer (diizinkan di SecurityConfig),
 * penyedia gateway memanggil dengan tanda tangannya sendiri. Level platform: TIDAK ada tenant di
 * path/context (tabel platform tanpa RLS). Penyedia diresolusi dari `{provider}` di path; adapter
 * memverifikasi tanda tangan callback, body yang tandanya salah ditolak 4xx tanpa menyentuh tagihan.
 */
@RestController
@RequestMapping("/api/platform/billing/webhooks")
@Tag(name = "Platform — Billing webhook")
class PlatformBillingWebhookController(
    private val registry: PaymentGatewayRegistry,
    private val resolver: PlatformGatewayResolver,
    private val paymentService: PlatformPaymentService,
) {
    @PostMapping("/{provider}")
    @Operation(summary = "Terima callback pelunasan langganan dari penyedia pembayaran")
    fun receive(
        @PathVariable provider: String,
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> {
        val ctx = resolver.resolve(provider)
            ?: throw NotFoundException("Gateway '$provider' belum dikonfigurasi/aktif di platform")
        val gateway = registry.forProvider(ctx.provider)
            ?: throw NotFoundException("Gateway '${ctx.provider}' tidak dikenal")
        val settlement = gateway.parseCallback(GatewayCallback(headers, body), ctx)
            ?: throw ValidationException("Callback ditolak")
        paymentService.applySettlement(settlement)
        return mapOf("status" to "ok")
    }
}
