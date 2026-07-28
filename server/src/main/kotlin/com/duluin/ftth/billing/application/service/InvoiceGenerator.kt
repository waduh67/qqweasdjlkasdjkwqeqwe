package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.billing.domain.model.Invoice
import com.duluin.ftth.billing.domain.model.Proration
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.customer.BillableSubscription
import com.duluin.ftth.customer.CustomerApi
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId
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

        // Gating tanggal penagihan per-langganan: paket boleh menimpa billingDayOfMonth
        // (null = ikut global). Langganan yang tanggal tagihnya belum tiba dilewati
        // ronde ini — akan terbit pada ronde berikutnya setelah tanggalnya tercapai.
        val billable = customerApi.findBillableSubscriptions().filter { sub ->
            sub.monthlyFee.signum() > 0 &&
                today.dayOfMonth >= (sub.billingDayOfMonth ?: properties.billingDayOfMonth) &&
                !invoiceRepository.existsForPeriod(sub.subscriptionId, periodStart)
        }
        if (billable.isEmpty()) return 0

        val customerNames = customerApi.findCustomersByIds(billable.mapTo(HashSet()) { it.customerId })
            .associate { it.id to it.name }
        val gateway = gatewayRegistry.default()
        val base = invoiceRepository.countForPeriod(periodStart)

        billable.forEachIndexed { index, sub ->
            val seq = base + index + 1
            val number = "${properties.numberPrefix}-$yyyyMM-${seq.toString().padStart(SEQ_WIDTH, '0')}"
            // Prorata dihitung sekali; nilainya menjadi satu-satunya sumber untuk
            // BAIK tagihan MAUPUN charge gateway (harus konsisten — kalau berbeda,
            // pelanggan membayar jumlah yang tak sama dengan tagihannya).
            val proration = prorationFor(sub, periodStart, periodEnd)
            val amount = proration?.amount ?: sub.monthlyFee
            val invoice = Invoice.create(
                tenantId = tenantId,
                customerId = sub.customerId,
                subscriptionId = sub.subscriptionId,
                number = number,
                periodStart = periodStart,
                periodEnd = periodEnd,
                amount = amount,
                dueDate = today.plusDays(properties.dueDays),
                prorated = proration != null,
                proratedDays = proration?.days,
            )
            val chargeDescription = if (proration != null) {
                "Tagihan ${sub.packageName} periode $yyyyMM (prorata ${proration.days} hari)"
            } else {
                "Tagihan ${sub.packageName} periode $yyyyMM"
            }
            val charge = gateway.createCharge(
                ChargeRequest(
                    invoiceNumber = number,
                    amount = amount,
                    customerName = customerNames[sub.customerId] ?: sub.packageName,
                    customerEmail = null,
                    description = chargeDescription,
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

    /**
     * Prorata untuk [sub] pada periode berjalan, atau null (tagih penuh). Aktif bila
     * flag paket [BillableSubscription.prorateOnActivation] menyala (null = ikut global
     * [BillingProperties.prorateOnActivation]) dan langganan punya `activatedAt` di dalam
     * periode. Tanggal aktivasi diambil di zona sistem — selaras dengan `today` penerbit.
     */
    private fun prorationFor(sub: BillableSubscription, periodStart: LocalDate, periodEnd: LocalDate): Proration? {
        val enabled = sub.prorateOnActivation ?: properties.prorateOnActivation
        if (!enabled) return null
        val activatedAt = sub.activatedAt ?: return null
        val activationDate = LocalDate.ofInstant(activatedAt, ZoneId.systemDefault())
        return Invoice.prorate(sub.monthlyFee, activationDate, periodStart, periodEnd)
    }

    private companion object {
        val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        const val SEQ_WIDTH = 4
    }
}
