package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.RecordPaymentUseCase
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Endpoint webhook pembayaran — TANPA autentikasi bearer (diizinkan di SecurityConfig),
 * karena penyedia gateway memanggil dengan tanda tangannya sendiri, bukan token kita.
 *
 * Tenant di-resolve dari slug di path (bukan dari JWT) lalu dipasang ke context agar
 * penulisan patuh RLS. Adapter gateway yang memverifikasi tanda tangan callback; body
 * yang tandanya salah/rusak ditolak 4xx tanpa menyentuh tagihan.
 */
@RestController
@RequestMapping("/api/billing/webhooks")
@Tag(name = "Billing — webhook pembayaran")
class BillingWebhookController(
    private val tenantApi: TenantApi,
    private val registry: PaymentGatewayRegistry,
    private val paymentService: RecordPaymentUseCase,
) {

    @PostMapping("/{tenantSlug}/{provider}")
    @Operation(summary = "Terima callback pelunasan dari penyedia pembayaran")
    fun receive(
        @PathVariable tenantSlug: String,
        @PathVariable provider: String,
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> {
        val tenant = tenantApi.findBySlug(tenantSlug)
            ?: throw NotFoundException("Tenant '$tenantSlug' tidak dikenal")
        return TenantContext.runAs(tenant.id) {
            val gateway = registry.forProvider(provider)
                ?: throw NotFoundException("Gateway '$provider' tidak dikenal")
            val settlement = gateway.parseCallback(GatewayCallback(headers, body))
                ?: throw ValidationException("Callback ditolak")
            paymentService.applySettlement(settlement)
            mapOf("status" to "ok")
        }
    }
}
