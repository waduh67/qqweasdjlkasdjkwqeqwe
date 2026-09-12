package com.duluin.ftth.order.adapter.inbound.web

import com.duluin.ftth.order.application.port.inbound.PublicOrderSubmission
import com.duluin.ftth.order.application.port.inbound.PublicOrderTrackView
import com.duluin.ftth.order.application.port.inbound.PublicOrderUseCase
import com.duluin.ftth.order.application.port.inbound.PublicPlanView
import com.duluin.ftth.order.application.port.inbound.PublicOrderReceipt
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Pemesanan oleh PENGUNJUNG — tanpa bearer, tanpa `@PreAuthorize`, tanpa `@SecurityRequirement`.
 *
 * KENAPA slug tenant ada di path, padahal rencana P4.5 menuliskan `/api/public/orders/track`
 * tanpa slug: nomor pesanan `ORD-YYMM-NNNN` hanya unik PER TENANT (UNIQUE `(tenant_id,
 * order_number)`). Tanpa slug, `ORD-2609-0001` menunjuk satu pesanan di setiap tenant sekaligus
 * dan pelacakan harus menyapu seluruh tenant untuk memilih salah satu — sebuah kebocoran
 * lintas-tenant yang menunggu terjadi, sekaligus query yang tak bisa dibantu RLS. Slug di path
 * juga membuat pola URL-nya seragam dengan halaman bayar publik yang sudah ada.
 *
 * Slug BUKAN rahasia dan tidak diperlakukan sebagai rahasia: ia hanya memilih tenant. Yang
 * menjaga data tetap nomor pesanan + nomor HP, ditambah rem laju.
 *
 * CATATAN OPERASIONAL: alamat IP diambil dari `remoteAddr`. Di belakang reverse proxy (Caddy)
 * itu adalah IP proxy-nya, sehingga rem per-IP melebur jadi satu ember untuk semua orang. Repo
 * ini belum memasang `ForwardedHeaderFilter`/`trusted-proxies`, dan memercayai `X-Forwarded-For`
 * tanpa daftar proxy tepercaya JUSTRU membuat remnya bisa dilewati siapa saja sekadar dengan
 * memalsukan header. Karena itu rem per-TENANT di [PublicOrderUseCase.submit] yang menjadi
 * pertahanan sesungguhnya sampai konfigurasi proxy tepercaya dipasang.
 */
@RestController
@RequestMapping("/api/public/orders")
@Tag(name = "Publik — Pemesanan pengunjung")
class PublicOrderController(
    private val useCase: PublicOrderUseCase,
) {

    @GetMapping("/{tenantSlug}/plans")
    @Operation(summary = "Paket yang masih dijual, untuk formulir pemesanan publik")
    fun plans(@PathVariable tenantSlug: String, servletRequest: HttpServletRequest): List<PublicPlanView> =
        useCase.plans(tenantSlug, servletRequest.remoteAddr)

    @PostMapping("/{tenantSlug}")
    @Operation(summary = "Kirim pesanan baru sebagai pengunjung (tanpa akun)")
    fun submit(
        @PathVariable tenantSlug: String,
        @Valid @RequestBody request: PublicOrderRequest,
        servletRequest: HttpServletRequest,
    ): PublicOrderReceipt = useCase.submit(tenantSlug, request.toSubmission(), servletRequest.remoteAddr)

    /**
     * GET, bukan POST: pengunjung membuka tautan lacak dari riwayat browser dan me-refresh-nya.
     * Nomor HP ikut di query string — ia memang akan tercatat di log akses, dan itu diterima
     * sadar: nomor HP bukan rahasia (pemiliknya menyebarkannya sendiri), sedangkan memaksa POST
     * membuat halaman lacak tak bisa di-bookmark tanpa menambah keamanan yang berarti.
     */
    @GetMapping("/{tenantSlug}/track")
    @Operation(summary = "Lacak status pesanan dengan nomor pesanan + nomor HP")
    fun track(
        @PathVariable tenantSlug: String,
        @RequestParam orderNumber: String,
        @RequestParam phone: String,
        servletRequest: HttpServletRequest,
    ): PublicOrderTrackView = useCase.track(tenantSlug, orderNumber, phone, servletRequest.remoteAddr)
}

/**
 * [website] adalah HONEYPOT. Ia dinamai seperti kolom formulir biasa supaya bot pengisi otomatis
 * tergoda mengisinya, lalu disembunyikan di UI. Manusia tak pernah melihatnya; permintaan yang
 * mengisinya ditolak. Bukan pertahanan yang kuat — hanya saringan termurah yang ada.
 */
data class PublicOrderRequest(
    @field:NotBlank @field:Size(max = 150) val name: String,
    @field:NotBlank @field:Size(max = 32) val phone: String,
    @field:Size(max = 200) val email: String? = null,
    val planId: UUID,
    @field:NotBlank @field:Size(max = 300) val address: String,
    @field:NotBlank @field:Size(max = 100) val city: String,
    @field:NotBlank @field:Size(max = 16) val postalCode: String,
    @field:DecimalMin("-90.0") @field:DecimalMax("90.0") val latitude: Double? = null,
    @field:DecimalMin("-180.0") @field:DecimalMax("180.0") val longitude: Double? = null,
    @field:Size(max = 1_000) val notes: String? = null,
    /** Kunci idempotensi milik klien; klik ganda tak boleh melahirkan dua pesanan. */
    @field:Size(max = 100) val requestId: String? = null,
    @field:Size(max = 200) val website: String? = null,
) {
    fun toSubmission() = PublicOrderSubmission(
        name = name, phone = phone, email = email, planId = planId,
        address = address, city = city, postalCode = postalCode,
        latitude = latitude, longitude = longitude, notes = notes,
        requestId = requestId, honeypot = website,
    )
}
