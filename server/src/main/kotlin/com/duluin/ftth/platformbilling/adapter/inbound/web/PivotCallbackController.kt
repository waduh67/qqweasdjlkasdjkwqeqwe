package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.billing.application.port.inbound.PivotCallbackApi
import com.duluin.ftth.billing.application.port.outbound.GatewayCallback
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.platformbilling.application.service.PlatformGatewayResolver
import com.duluin.ftth.platformbilling.application.service.PlatformPaymentService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Titik masuk TUNGGAL semua callback Pivot — TANPA bearer (diizinkan di SecurityConfig): Pivot
 * memanggil dengan `X-API-Key` master-nya sendiri, diverifikasi tiap handler (constant-time).
 *
 * Akun MASTER Pivot mendaftarkan SATU Callback URL per PRODUK (bukan per-tenant), jadi tak ada slug
 * di path — tenant di-resolve dari payload di lapis billing ([PivotCallbackApi]). Controller ini
 * hidup di `platformbilling` karena hanya di sini kedua sisi bertemu: callback pembayaran yang BUKAN
 * milik tenant (`scope != TENANT`) adalah pelunasan langganan SaaS, disetel lewat
 * [PlatformPaymentService]. Semua produk wall* tak dipakai bisnis → cukup verifikasi + ACK 200.
 *
 * Semua handler membalas 200 `{"status": ...}` bila tanda tangan sah; body tak sah dilempar 4xx oleh
 * facade tanpa efek. Setiap produk WAJIB punya endpoint hidup agar retry Pivot berhenti (ACK).
 */
@RestController
@RequestMapping("/api/platform/pivot/callbacks")
@Tag(name = "Platform — Callback Pivot")
class PivotCallbackController(
    private val callbacks: PivotCallbackApi,
    private val platformResolver: PlatformGatewayResolver,
    private val registry: PaymentGatewayRegistry,
    private val platformPayments: PlatformPaymentService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/payment")
    @Operation(summary = "Callback produk PAYMENT (pelunasan tagihan pelanggan & langganan SaaS)")
    fun payment(
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> {
        // true = charge tenant, sudah disetel di lapis billing. false = langganan SaaS → setel di sini.
        if (callbacks.handlePayment(headers, body)) return ack("settled")
        return settleSaas(headers, body)
    }

    @PostMapping("/payout")
    @Operation(summary = "Callback produk PAYOUT (penyaluran dana ke rekening tenant)")
    fun payout(@RequestBody body: String, @RequestHeader headers: Map<String, String>): Map<String, String> =
        disbursement(headers, body)

    @PostMapping("/withdrawal")
    @Operation(summary = "Callback produk WITHDRAWAL (tarik saldo sub-account KYC)")
    fun withdrawal(@RequestBody body: String, @RequestHeader headers: Map<String, String>): Map<String, String> =
        disbursement(headers, body)

    @PostMapping("/international-payout")
    @Operation(summary = "Callback produk INTERNATIONAL_PAYOUT (rekonsiliasi seperti payout)")
    fun internationalPayout(
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> = disbursement(headers, body)

    @PostMapping("/refund")
    @Operation(summary = "Callback produk REFUND (diverifikasi & dicatat, belum diproses)")
    fun refund(@RequestBody body: String, @RequestHeader headers: Map<String, String>): Map<String, String> {
        callbacks.handleRefund(headers, body)
        return ack("acknowledged")
    }

    @PostMapping("/sub-account-registration")
    @Operation(summary = "Callback produk SUB_ACCOUNT_REGISTRATION (status & KYC sub-account)")
    fun subAccountRegistration(
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> =
        ack(if (callbacks.handleSubAccountRegistration(headers, body)) "updated" else "ignored")

    // Produk wallet TIDAK dipakai model bisnis ini — tetap wajib punya endpoint hidup: verifikasi
    // tanda tangan lalu ACK 200 (no-op) agar Pivot berhenti retry.
    @PostMapping("/wallet")
    @Operation(summary = "Callback produk WALLET (tak dipakai — verifikasi + ACK)")
    fun wallet(@RequestBody body: String, @RequestHeader headers: Map<String, String>): Map<String, String> =
        acknowledgeOnly(headers, "wallet")

    @PostMapping("/wallets")
    @Operation(summary = "Callback produk WALLETS (tak dipakai — verifikasi + ACK)")
    fun wallets(@RequestBody body: String, @RequestHeader headers: Map<String, String>): Map<String, String> =
        acknowledgeOnly(headers, "wallets")

    @PostMapping("/wallet-account-linkage-activation")
    @Operation(summary = "Callback produk WALLET_ACCOUNT_LINKAGE_ACTIVATION (tak dipakai — verifikasi + ACK)")
    fun walletAccountLinkageActivation(
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> = acknowledgeOnly(headers, "wallet-account-linkage-activation")

    @PostMapping("/wallet-user-activation")
    @Operation(summary = "Callback produk WALLET_USER_ACTIVATION (tak dipakai — verifikasi + ACK)")
    fun walletUserActivation(
        @RequestBody body: String,
        @RequestHeader headers: Map<String, String>,
    ): Map<String, String> = acknowledgeOnly(headers, "wallet-user-activation")

    private fun disbursement(headers: Map<String, String>, body: String): Map<String, String> =
        ack(if (callbacks.handleDisbursement(headers, body)) "reconciled" else "ignored")

    /** Pelunasan langganan SaaS: charge di akun master tanpa tenant, disetel di lapis platform. */
    private fun settleSaas(headers: Map<String, String>, body: String): Map<String, String> {
        val ctx = platformResolver.resolve("PIVOT") ?: return ack("ignored")
        val gateway = registry.forProvider(ctx.provider) ?: return ack("ignored")
        val settlement = gateway.parseCallback(GatewayCallback(headers, body), ctx)
        if (settlement == null) {
            log.info("Callback payment SaaS bukan pelunasan / tak terparse — di-ACK")
            return ack("ignored")
        }
        platformPayments.applySettlement(settlement)
        return ack("settled")
    }

    /** Verifikasi tanda tangan saja lalu ACK (produk yang tak diproses billing). */
    private fun acknowledgeOnly(headers: Map<String, String>, product: String): Map<String, String> {
        callbacks.verifySignature(headers)
        log.info("Callback produk '{}' diterima — tak dipakai, di-ACK (no-op)", product)
        return ack("acknowledged")
    }

    private fun ack(status: String) = mapOf("status" to status)
}
