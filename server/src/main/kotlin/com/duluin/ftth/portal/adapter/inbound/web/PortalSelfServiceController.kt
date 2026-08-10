package com.duluin.ftth.portal.adapter.inbound.web

import com.duluin.ftth.portal.application.port.inbound.PortalAccountView
import com.duluin.ftth.portal.application.port.inbound.PortalBillingView
import com.duluin.ftth.portal.application.port.inbound.PortalConnectionView
import com.duluin.ftth.portal.application.port.inbound.PortalInvoicePrintView
import com.duluin.ftth.portal.application.port.inbound.PortalInvoiceView
import com.duluin.ftth.portal.application.port.inbound.PortalPaymentMethodView
import com.duluin.ftth.portal.application.port.inbound.PortalPlanChangeCommand
import com.duluin.ftth.portal.application.port.inbound.PortalPlanChangeReceiptView
import com.duluin.ftth.portal.application.port.inbound.PortalPlanOptionView
import com.duluin.ftth.portal.application.port.inbound.PortalSelfServiceUseCase
import com.duluin.ftth.portal.security.CurrentPortalCustomer
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Query self-service pelanggan (path berawalan `/api/portal/me/`). Semua endpoint MEMBACA
 * data pelanggan yang SEDANG login — id diambil dari principal portal, tak pernah dari path
 * atau query, sehingga pelanggan mustahil membaca data pelanggan lain. Rantai keamanan +
 * dekoder token: [com.duluin.ftth.portal.adapter.inbound.security.PortalSecurityConfig].
 */
@RestController
@RequestMapping("/api/portal/me")
@Tag(name = "Portal")
class PortalSelfServiceController(
    private val selfService: PortalSelfServiceUseCase,
    private val currentPortalCustomer: CurrentPortalCustomer,
) {

    /** Profil + langganan beserta detail paket (Profil & paket). */
    @GetMapping("/profile")
    fun profile(): PortalAccountView = selfService.profile(currentCustomerId())

    /** Ringkasan rekening + tagihan (tautan bayar) + riwayat pembayaran (Tagihan & Bayar online). */
    @GetMapping("/billing")
    fun billing(): PortalBillingView = selfService.billing(currentCustomerId())

    /** Metode bayar in-app yang tersedia (QRIS + Virtual Account). */
    @GetMapping("/payment-methods")
    fun paymentMethods(): List<PortalPaymentMethodView> = selfService.paymentMethods(currentCustomerId())

    /**
     * Buat charge in-app (VA/QRIS) untuk satu tagihan pelanggan yang login lalu kembalikan tagihan
     * berisi instruksi bayar. Dibatasi ke pelanggan berjalan — tak bisa membayar tagihan orang lain.
     */
    @PostMapping("/invoices/{invoiceId}/pay")
    fun pay(
        @PathVariable invoiceId: UUID,
        @RequestBody request: PortalPayInvoiceRequest,
    ): PortalInvoiceView = selfService.payInvoice(currentCustomerId(), invoiceId, request.method, request.channel)

    /** Sesi PPPoE terkini + perangkat CPE (Status koneksi). */
    @GetMapping("/connection")
    fun connection(): PortalConnectionView = selfService.connection(currentCustomerId())

    /**
     * Lembar tagihan siap cetak (penerbit, penerima, rincian pajak, pembayaran masuk). Server yang
     * merakitnya utuh — klien hanya menatanya — supaya isi lembar cetak tak bergantung tampilan.
     */
    @GetMapping("/invoices/{invoiceId}/print")
    fun invoicePrint(@PathVariable invoiceId: UUID): PortalInvoicePrintView =
        selfService.invoiceForPrint(currentCustomerId(), invoiceId)

    /** Paket aktif yang bisa dipilih saat mengajukan ganti paket, dengan penanda paket sekarang. */
    @GetMapping("/plan-options")
    fun planOptions(): List<PortalPlanOptionView> = selfService.planOptions(currentCustomerId())

    /**
     * Ajukan pindah paket. TIDAK mengubah langganan — ajuan masuk sebagai tiket `GANTI_PAKET`
     * agar operator memutuskan harga/prorata/kunjungan, dan pelanggan dapat nomor untuk diikuti.
     */
    @PostMapping("/plan-change")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestPlanChange(@Valid @RequestBody request: PortalPlanChangeRequest): PortalPlanChangeReceiptView =
        selfService.requestPlanChange(
            currentCustomerId(),
            PortalPlanChangeCommand(
                subscriptionId = request.subscriptionId!!,
                targetPlanId = request.targetPlanId!!,
                note = request.note,
            ),
        )

    private fun currentCustomerId() = currentPortalCustomer.current().customerId
}

/** Pilihan instrumen bayar in-app: [method] = VIRTUAL_ACCOUNT/QR, [channel] bank (wajib utk VA). */
data class PortalPayInvoiceRequest(val method: String, val channel: String?)

/** Ajuan ganti paket; [note] alasan pelanggan (opsional) yang ikut jadi badan tiket. */
data class PortalPlanChangeRequest(
    @field:NotNull val subscriptionId: UUID? = null,
    @field:NotNull val targetPlanId: UUID? = null,
    @field:Size(max = 1000) val note: String? = null,
)
