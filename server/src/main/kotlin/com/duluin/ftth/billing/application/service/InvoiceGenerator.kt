package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.CustomerApi
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Mesin penerbitan tagihan periode berjalan, dipakai bersama oleh trigger manual
 * (service) dan scheduler. Langganan yang ditagih ditarik lewat [CustomerApi] —
 * module billing tak menyentuh agregat langganan; ia bertanya lewat kontrak lintas
 * module dan menerima hanya langganan tenant aktif (ter-scope RLS).
 *
 * Anti-duplikat dijaga dua lapis: [InvoiceRepository.existsForPeriod] menyaring
 * langganan yang sudah punya tagihan periode ini, dan unique (tenant, subscription,
 * period_start) di DB menjadi pengaman terakhir.
 */
@Component
class InvoiceGenerator(
    private val invoiceRepository: InvoiceRepository,
    private val customerApi: CustomerApi,
    private val gatewayRegistry: PaymentGatewayRegistry,
    private val auditor: AuditRecorder,
    private val properties: BillingProperties,
) {

    /**
     * Terbitkan tagihan periode bulan [today] untuk [tenantId]. Nomor urut bersifat
     * global-per-periode (lanjut dari jumlah tagihan periode yang sudah ada) agar
     * penerbitan susulan tidak bentrok nomor. Mengembalikan jumlah tagihan yang dibuat.
     */
    fun generateFor(tenantId: UUID, today: LocalDate): Int {
        val periodStart = today.withDayOfMonth(1)
        val periodEnd = today.withDayOfMonth(today.lengthOfMonth())
        val yyyyMM = periodStart.format(YEAR_MONTH)

        val billable = customerApi.findBillableSubscriptions()
            .filter { it.monthlyFee.signum() > 0 && !invoiceRepository.existsForPeriod(it.subscriptionId, periodStart) }
        if (billable.isEmpty()) return 0

        val customerNames = customerApi.findCustomersByIds(billable.mapTo(HashSet()) { it.customerId })
            .associate { it.id to it.name }
        val gateway = gatewayRegistry.default()
        val base = invoiceRepository.countForPeriod(periodStart)

        billable.forEachIndexed { index, sub ->
            val seq = base + index + 1
            val number = "${properties.numberPrefix}-$yyyyMM-${seq.toString().padStart(SEQ_WIDTH, '0')}"
            val invoice = Invoice.create(
                tenantId = tenantId,
                customerId = sub.customerId,
                subscriptionId = sub.subscriptionId,
                number = number,
                periodStart = periodStart,
                periodEnd = periodEnd,
                amount = sub.monthlyFee,
                dueDate = today.plusDays(properties.dueDays),
            )
            val charge = gateway.createCharge(
                ChargeRequest(
                    invoiceNumber = number,
                    amount = sub.monthlyFee,
                    customerName = customerNames[sub.customerId] ?: sub.packageName,
                    customerEmail = null,
                    description = "Tagihan ${sub.packageName} periode $yyyyMM",
                ),
            )
            invoice.attachCharge(charge.provider, charge.gatewayRef, charge.payUrl)
            val saved = invoiceRepository.save(invoice)
            auditor.record(
                "billing.invoice.issued", "Invoice", saved.id, saved.tenantId,
                mapOf("number" to saved.number, "amount" to saved.amount),
            )
        }
        return billable.size
    }

    private companion object {
        val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        const val SEQ_WIDTH = 4
    }
}
