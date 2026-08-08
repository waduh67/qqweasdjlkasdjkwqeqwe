package com.duluin.ftth.platformbilling.adapter.inbound.web

import com.duluin.ftth.billing.PaymentMethodOption
import com.duluin.ftth.platformbilling.application.port.inbound.PublicInvoicePaymentUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.PublicInvoiceView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Halaman bayar publik — TANPA bearer (diizinkan di `SecurityConfig`) dan tanpa `@PreAuthorize`:
 * kapabilitasnya adalah UUID tagihan di path, bukan token. Inilah yang membuat tautan bayar bisa
 * dikirim ke pelanggan lewat WhatsApp dan dibuka di ponsel mana pun.
 *
 * Slug tenant ikut di path karena tabel `invoice` ber-RLS FORCE: dari UUID saja tenant tak bisa
 * disimpulkan (lihat [PublicInvoicePaymentUseCase]). Satu bentuk endpoint melayani dua jenis
 * tagihan (pelanggan & langganan SaaS).
 */
@RestController
@RequestMapping("/api/public/invoices")
@Tag(name = "Publik — Halaman bayar")
class PublicInvoicePaymentController(
    private val useCase: PublicInvoicePaymentUseCase,
) {
    @GetMapping("/{tenantSlug}/{invoiceId}")
    @Operation(summary = "Tagihan pada tautan bayar publik (juga dipakai polling status pelunasan)")
    fun get(@PathVariable tenantSlug: String, @PathVariable invoiceId: UUID): PublicInvoiceView =
        useCase.find(tenantSlug, invoiceId)

    @GetMapping("/{tenantSlug}/{invoiceId}/methods")
    @Operation(summary = "Metode bayar in-app yang ditawarkan (QRIS & Virtual Account)")
    fun methods(@PathVariable tenantSlug: String, @PathVariable invoiceId: UUID): List<PaymentMethodOption> {
        // Katalognya statis, tapi tautan tetap divalidasi agar endpoint ini tak jadi probe slug.
        useCase.find(tenantSlug, invoiceId)
        return useCase.paymentMethods()
    }

    @PostMapping("/{tenantSlug}/{invoiceId}/pay")
    @Operation(summary = "Pilih instrumen bayar & ambil instruksinya (VA/QRIS)")
    fun pay(
        @PathVariable tenantSlug: String,
        @PathVariable invoiceId: UUID,
        @Valid @RequestBody request: PublicPayRequest,
    ): PublicInvoiceView = useCase.pay(tenantSlug, invoiceId, request.method, request.channel)

    @GetMapping("/{tenantSlug}/{invoiceId}/qris")
    @Operation(summary = "Gambar QRIS statis tenant (pembayaran manual)")
    fun qrisImage(@PathVariable tenantSlug: String, @PathVariable invoiceId: UUID): ResponseEntity<ByteArray> {
        val image = useCase.manualQrisImage(tenantSlug, invoiceId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType)).body(image.bytes)
    }
}

/** Pilihan instrumen bayar: [method] `VIRTUAL_ACCOUNT`/`QR`; [channel] bank (wajib untuk VA). */
data class PublicPayRequest(
    @field:NotBlank @field:Size(max = 40) val method: String,
    @field:Size(max = 40) val channel: String? = null,
)
