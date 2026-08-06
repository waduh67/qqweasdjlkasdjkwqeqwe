package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.inbound.InvoiceView
import com.duluin.ftth.billing.application.port.inbound.ManageInvoiceUseCase
import com.duluin.ftth.billing.application.port.inbound.PaymentView
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.application.port.outbound.PaymentRepository
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.InvoiceStatus
import com.duluin.ftth.billing.domain.model.Payment
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class InvoiceService(
    private val invoiceRepository: InvoiceRepository,
    private val paymentRepository: PaymentRepository,
    private val invoiceGenerator: InvoiceGenerator,
    private val auditor: AuditRecorder,
) : ManageInvoiceUseCase {

    @Transactional(readOnly = true)
    override fun list(customerId: UUID?, status: InvoiceStatus?): List<InvoiceView> {
        val invoices = when {
            customerId != null -> invoiceRepository.findByCustomerId(customerId)
            status != null -> invoiceRepository.findByStatus(status)
            else -> invoiceRepository.findAll()
        }
        // Bila kedua filter diberikan, saring status di memori atas hasil per pelanggan.
        val filtered = if (customerId != null && status != null) invoices.filter { it.status == status } else invoices
        return filtered.map { it.toView() }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): InvoiceView = require(id).toView()

    @Transactional(readOnly = true)
    override fun payments(invoiceId: UUID): List<PaymentView> =
        paymentRepository.findByInvoiceId(invoiceId).map { it.toView() }

    override fun generateCurrentPeriodForCurrentTenant(): Int =
        invoiceGenerator.generateFor(TenantContext.tenantId(), java.time.LocalDate.now())

    override fun void(id: UUID): InvoiceView {
        val invoice = require(id)
        invoice.void()
        val saved = invoiceRepository.save(invoice)
        auditor.record("billing.invoice.voided", "Invoice", saved.id, saved.tenantId, mapOf("number" to saved.number))
        return saved.toView()
    }

    override fun refreshPaymentLink(id: UUID): InvoiceView {
        val invoice = require(id)
        if (invoice.status == InvoiceStatus.PAID || invoice.status == InvoiceStatus.VOID) {
            throw ConflictException("Tagihan berstatus ${invoice.status} tidak bisa dibuatkan tautan bayar")
        }
        val changed = invoiceGenerator.refreshCharge(invoice)
        if (!changed) return invoice.toView()
        val saved = invoiceRepository.save(invoice)
        auditor.record(
            "billing.invoice.recharged", "Invoice", saved.id, saved.tenantId,
            mapOf("number" to saved.number, "provider" to (saved.gatewayProvider ?: "-")),
        )
        return saved.toView()
    }

    private fun require(id: UUID): Invoice =
        invoiceRepository.findById(id) ?: throw NotFoundException("Tagihan $id tidak ditemukan")
}

internal fun Invoice.toView() = InvoiceView(
    id = id,
    number = number,
    customerId = customerId,
    subscriptionId = subscriptionId,
    periodStart = periodStart,
    periodEnd = periodEnd,
    amount = amount,
    baseAmount = baseAmount,
    taxAmount = taxAmount,
    taxRate = taxRate,
    prorated = prorated,
    proratedDays = proratedDays,
    status = status.name,
    issuedAt = issuedAt,
    dueDate = dueDate,
    paidAt = paidAt,
    gatewayProvider = gatewayProvider,
    payUrl = payUrl,
)

internal fun Payment.toView() = PaymentView(
    id = id,
    invoiceId = invoiceId,
    customerId = customerId,
    amount = amount,
    provider = provider,
    gatewayRef = gatewayRef,
    paidAt = paidAt,
    note = note,
)
