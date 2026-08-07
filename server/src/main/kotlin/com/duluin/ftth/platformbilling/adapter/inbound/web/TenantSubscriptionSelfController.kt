package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.PaymentMethodOption
import com.duluin.ftth.platformbilling.application.port.inbound.SubscriptionInvoiceView
import com.duluin.ftth.platformbilling.application.port.inbound.TenantSelfSubscriptionUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.TenantSelfSubscriptionView
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Halaman langganan sisi TENANT: tenant admin melihat masa aktif/tagihan langganan aplikasinya
 * sendiri dan memperpanjang mandiri lewat gateway aktif. Selalu untuk tenant konteks berjalan —
 * tak ada parameter tenantId. Dijaga izin `billing.subscription.*`.
 */
@RestController
@RequestMapping("/api/subscription")
@Tag(name = "Langganan (Tenant)")
@SecurityRequirement(name = "bearer-jwt")
class TenantSubscriptionSelfController(
    private val useCase: TenantSelfSubscriptionUseCase,
) {
    /** 200 dengan langganan, atau 204 bila tenant belum berlangganan. */
    @GetMapping
    @PreAuthorize("@authz.can('billing.subscription.view')")
    fun get(): ResponseEntity<TenantSelfSubscriptionView> =
        useCase.current()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    /**
     * Terbitkan/ambil tagihan untuk dibayar; kembalikan tautan bayar gateway. [months] = jumlah
     * bulan dibayar di muka (default 1) — nilai tagihan `biaya × months`.
     */
    @PostMapping("/renew")
    @PreAuthorize("@authz.can('billing.subscription.renew')")
    fun renew(@RequestParam(defaultValue = "1") months: Int): SubscriptionInvoiceView = useCase.renew(months)

    /** Metode bayar in-app yang tersedia (QRIS + Virtual Account). */
    @GetMapping("/payment-methods")
    @PreAuthorize("@authz.can('billing.subscription.view')")
    fun paymentMethods(): List<PaymentMethodOption> = PaymentMethodCatalog.methods

    /**
     * Buat charge in-app (VA/QRIS) untuk satu tagihan tertunggak lalu kembalikan tagihan berisi
     * instruksi bayar (nomor VA / string QRIS). Dipakai tombol "Bayar" per-tagihan di Riwayat tagihan.
     */
    @PostMapping("/invoices/{invoiceId}/pay")
    @PreAuthorize("@authz.can('billing.subscription.renew')")
    fun pay(
        @PathVariable invoiceId: UUID,
        @RequestBody request: PaySubscriptionInvoiceRequest,
    ): SubscriptionInvoiceView = useCase.payInvoice(invoiceId, request.method, request.channel)
}

/** Pilihan instrumen bayar in-app: [method] = VIRTUAL_ACCOUNT/QR, [channel] bank (wajib utk VA). */
data class PaySubscriptionInvoiceRequest(val method: String, val channel: String?)
