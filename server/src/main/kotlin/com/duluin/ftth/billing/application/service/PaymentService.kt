package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.InvoiceView
import com.duluin.ftth.billing.application.port.inbound.RecordPaymentUseCase
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.CustomerApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional
class PaymentService(
    private val invoiceRepository: InvoiceRepository,
    private val paymentRepository: PaymentRepository,
    private val customerApi: CustomerApi,
    private val auditor: AuditRecorder,
) : RecordPaymentUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun applySettlement(settlement: PaymentSettlement) {
        val invoice = invoiceRepository.findByNumber(settlement.invoiceNumber)
        if (invoice == null) {
            log.warn("Settlement diabaikan — nomor tagihan '{}' tidak dikenal", settlement.invoiceNumber)
            return
        }
        if (invoice.status == InvoiceStatus.PAID) return
        settle(invoice, settlement, note = null)
    }

    override fun recordManual(invoiceId: UUID, note: String?): InvoiceView {
        val invoice = invoiceRepository.findById(invoiceId)
            ?: throw NotFoundException("Tagihan $invoiceId tidak ditemukan")
        val settlement = PaymentSettlement(
            invoiceNumber = invoice.number,
            gatewayRef = null,
            amount = invoice.amount,
            paidAt = Instant.now(),
            provider = "MANUAL",
        )
        return settle(invoice, settlement, note)
    }

    /**
     * Jalur pelunasan bersama: tandai lunas, catat pembayaran, lalu auto-pulih bila
     * tak ada lagi tagihan menunggak langganan ini. Idempoten — tagihan yang sudah
     * lunas tidak menghasilkan pembayaran ganda.
     */
    private fun settle(invoice: Invoice, settlement: PaymentSettlement, note: String?): InvoiceView {
        if (invoice.status == InvoiceStatus.PAID) return invoice.toView()

        invoice.markPaid(settlement.paidAt)
        val saved = invoiceRepository.save(invoice)
        paymentRepository.save(
            Payment.create(
                tenantId = saved.tenantId,
                invoiceId = saved.id,
                customerId = saved.customerId,
                amount = settlement.amount,
                provider = settlement.provider,
                gatewayRef = settlement.gatewayRef,
                paidAt = settlement.paidAt,
                note = note,
            ),
        )
        auditor.record(
            "billing.invoice.paid", "Invoice", saved.id, saved.tenantId,
            mapOf("number" to saved.number, "provider" to settlement.provider),
        )

        // Auto-pulih: lunas menghapus alasan isolir hanya bila tak ada tunggakan tersisa.
        if (!invoiceRepository.hasOverdueForSubscription(saved.subscriptionId)) {
            customerApi.reactivateForBilling(saved.subscriptionId)
        }
        return saved.toView()
    }
}
