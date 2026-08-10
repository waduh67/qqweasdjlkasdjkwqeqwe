package com.duluin.ftth.billing.adapter.inbound.web

import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.PaymentMethodOption
import com.duluin.ftth.billing.application.port.inbound.InvoiceView
import com.duluin.ftth.billing.application.port.inbound.ManageInvoiceUseCase
import com.duluin.ftth.billing.application.port.inbound.ManageRefundUseCase
import com.duluin.ftth.billing.application.port.inbound.PaymentView
import com.duluin.ftth.billing.application.port.inbound.RecordPaymentUseCase
import com.duluin.ftth.billing.application.port.inbound.RefundView
import com.duluin.ftth.billing.application.port.inbound.RequestRefundCommand
import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.RefundReason
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * Billing: telusur tagihan & pembayaran, penerbitan/pembatalan tagihan, dan catatan
 * pembayaran manual. Aturan nilai & perpindahan status ditegakkan di domain; controller
 * hanya menautkan izin dan bentuk request/response.
 */
@RestController
@RequestMapping("/api/billing")
@Tag(name = "Billing — tagihan & pembayaran")
@SecurityRequirement(name = "bearer-jwt")
class BillingController(
    private val invoices: ManageInvoiceUseCase,
    private val payments: RecordPaymentUseCase,
    private val refunds: ManageRefundUseCase,
) {

    @GetMapping("/invoices")
    @PreAuthorize("@authz.can('billing.invoice.view')")
    @Operation(summary = "Daftar tagihan (boleh saring per pelanggan/status)")
    fun listInvoices(
        @RequestParam(required = false) customerId: UUID?,
        @RequestParam(required = false) status: InvoiceStatus?,
    ): List<InvoiceView> = invoices.list(customerId, status)

    @GetMapping("/invoices/{id}")
    @PreAuthorize("@authz.can('billing.invoice.view')")
    @Operation(summary = "Detail satu tagihan")
    fun getInvoice(@PathVariable id: UUID): InvoiceView = invoices.get(id)

    @PostMapping("/invoices/generate")
    @PreAuthorize("@authz.can('billing.invoice.manage')")
    @Operation(summary = "Terbitkan tagihan periode berjalan untuk tenant aktif")
    fun generate(): GenerateResult = GenerateResult(invoices.generateCurrentPeriodForCurrentTenant())

    @PostMapping("/invoices/{id}/void")
    @PreAuthorize("@authz.can('billing.invoice.manage')")
    @Operation(summary = "Batalkan tagihan (ditolak bila sudah lunas)")
    fun voidInvoice(@PathVariable id: UUID): InvoiceView = invoices.void(id)

    @GetMapping("/payment-methods")
    @PreAuthorize("@authz.can('billing.invoice.view')")
    @Operation(summary = "Metode bayar in-app yang tersedia (QRIS + Virtual Account)")
    fun paymentMethods(): List<PaymentMethodOption> = PaymentMethodCatalog.methods

    @PostMapping("/invoices/{id}/recharge")
    @PreAuthorize("@authz.can('billing.invoice.manage')")
    @Operation(summary = "Buat charge in-app (VA/QRIS) untuk tagihan lewat gateway aktif")
    fun recharge(@PathVariable id: UUID, @RequestBody request: ChargeInvoiceRequest): InvoiceView =
        invoices.chargeInvoice(id, request.method, request.channel)

    @PostMapping("/invoices/{id}/pay")
    @PreAuthorize("@authz.can('billing.payment.manage')")
    @Operation(summary = "Catat pembayaran manual sebuah tagihan")
    fun pay(@PathVariable id: UUID, @RequestBody(required = false) request: RecordPaymentRequest?): InvoiceView =
        payments.recordManual(id, request?.note)

    @PostMapping("/invoices/{id}/simulate")
    @PreAuthorize("@authz.can('billing.invoice.manage')")
    @Operation(summary = "Simulasi pembayaran (sandbox): paksa sesi bayar jadi SUCCESS/EXPIRED")
    fun simulate(@PathVariable id: UUID, @RequestBody request: SimulatePaymentRequest): InvoiceView =
        invoices.simulatePayment(id, request.status)

    @GetMapping("/payments")
    @PreAuthorize("@authz.can('billing.invoice.view')")
    @Operation(summary = "Pembayaran yang tercatat atas sebuah tagihan")
    fun listPayments(@RequestParam invoiceId: UUID): List<PaymentView> = invoices.payments(invoiceId)

    @GetMapping("/refunds")
    @PreAuthorize("@authz.can('billing.invoice.view')")
    @Operation(summary = "Pengembalian dana (boleh disaring per tagihan)")
    fun listRefunds(@RequestParam(required = false) invoiceId: UUID?): List<RefundView> = refunds.list(invoiceId)

    @PostMapping("/invoices/{id}/refund")
    @PreAuthorize("@authz.can('billing.refund.manage')")
    @Operation(summary = "Ajukan pengembalian dana (nominal kosong = seluruh sisa tagihan)")
    fun refund(@PathVariable id: UUID, @RequestBody(required = false) request: RefundInvoiceRequest?): RefundView =
        refunds.request(
            RequestRefundCommand(
                invoiceId = id,
                amount = request?.amount,
                reason = request?.reason ?: RefundReason.REQUESTED_BY_CUSTOMER,
                note = request?.note,
            ),
        )

    @PostMapping("/refunds/{id}/settle")
    @PreAuthorize("@authz.can('billing.refund.manage')")
    @Operation(summary = "Tutup pengembalian MANUAL: nyatakan transfer baliknya berhasil/gagal")
    fun settleRefund(@PathVariable id: UUID, @RequestBody request: SettleRefundRequest): RefundView =
        refunds.settleManual(id, request.success, request.reason)
}

/** Ringkasan hasil penerbitan massal: berapa tagihan yang dibuat. */
data class GenerateResult(val created: Int)

data class RecordPaymentRequest(val note: String?)

/** Pilihan instrumen bayar in-app: [method] = VIRTUAL_ACCOUNT/QR, [channel] bank (wajib utk VA). */
data class ChargeInvoiceRequest(val method: String, val channel: String?)

/** Status akhir yang dipaksakan ke sesi bayar saat simulasi sandbox: SUCCESS atau EXPIRED. */
data class SimulatePaymentRequest(val status: SimulatedChargeStatus)

/**
 * Pengajuan refund. [amount] kosong = seluruh sisa yang masih bisa dikembalikan (jalur paling sering);
 * diisi = refund sebagian. [reason] mengikuti alasan baku penyedia, [note] catatan internal operator.
 */
data class RefundInvoiceRequest(
    val amount: BigDecimal? = null,
    val reason: RefundReason? = null,
    val note: String? = null,
)

/** Penutupan refund MANUAL: [success] false wajib disertai [reason] agar jejaknya bisa dibaca. */
data class SettleRefundRequest(val success: Boolean, val reason: String? = null)
