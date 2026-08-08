package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.platformbilling.application.port.inbound.ConfigureSubscriptionCommand
import com.duluin.ftth.platformbilling.application.port.inbound.ManageTenantSubscriptionUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.ManualPaymentCommand
import com.duluin.ftth.platformbilling.application.port.inbound.SubscriptionInvoiceView
import com.duluin.ftth.platformbilling.application.port.inbound.TenantSubscriptionDetailView
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.TenantSubscription
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Sisi super-admin pengelolaan langganan tenant: set biaya bulanan, terbitkan/batalkan tagihan,
 * catat pembayaran manual, hentikan langganan. Penerbitan & pelunasan didelegasikan ke
 * [PlatformInvoiceGenerator]/[PlatformPaymentService] agar konsisten dengan jalur scheduler/webhook.
 */
@Service
@Transactional(readOnly = true)
class TenantSubscriptionService(
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val invoiceRepository: TenantSubscriptionInvoiceRepository,
    private val invoiceGenerator: PlatformInvoiceGenerator,
    private val paymentService: PlatformPaymentService,
    private val tenantApi: TenantApi,
    private val masterConfig: PivotMasterConfigProvider,
    private val auditor: AuditRecorder,
) : ManageTenantSubscriptionUseCase {

    override fun get(tenantId: UUID): TenantSubscriptionDetailView? =
        subscriptionRepository.findByTenantId(tenantId)?.toDetailView()

    @Transactional
    override fun configure(tenantId: UUID, command: ConfigureSubscriptionCommand): TenantSubscriptionDetailView {
        // Pastikan tenant ada (melempar NotFound bila tidak).
        tenantApi.requireById(tenantId)
        val existing = subscriptionRepository.findByTenantId(tenantId)
        val subscription = existing?.apply {
            configure(command.monthlyFee, command.billingDay, command.graceDays)
        } ?: TenantSubscription.create(
            tenantId = tenantId,
            monthlyFee = command.monthlyFee,
            billingDay = command.billingDay,
            graceDays = command.graceDays,
        ).apply {
            // Langganan baru: jadwalkan tagihan pertama mulai hari ini.
            scheduleNextInvoice(LocalDate.now())
        }
        val saved = subscriptionRepository.save(subscription)
        // Langganan yang sudah ada: biaya baru ikut menyesuaikan tagihan yang belum lunas & belum
        // di-charge (nilai = biaya bulanan × jumlah bulan periode). `reprice` no-op bila tak berubah.
        if (existing != null) {
            invoiceRepository.findOutstandingBySubscriptionId(saved.id).forEach { invoice ->
                val span = ChronoUnit.MONTHS.between(invoice.periodStart, invoice.periodEnd.plusDays(1))
                    .coerceAtLeast(1)
                if (invoice.reprice(saved.monthlyFee.multiply(BigDecimal.valueOf(span)))) {
                    invoiceRepository.save(invoice)
                }
            }
        }
        auditor.record(
            action = "platform.subscription.configured",
            entityType = "TenantSubscription",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("tenantId" to tenantId.toString(), "monthlyFee" to saved.monthlyFee.toPlainString()),
        )
        return saved.toDetailView()
    }

    @Transactional
    override fun generateInvoice(tenantId: UUID): SubscriptionInvoiceView {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw NotFoundException("Tenant belum berlangganan — set biaya bulanan dulu")
        // Idempoten: bila masih ada tagihan belum lunas, kembalikan itu alih-alih menerbitkan
        // yang baru (hindari tagihan dobel saat "Terbitkan tagihan" diklik berulang) — seragam
        // dengan jalur perpanjangan mandiri tenant (TenantSelfSubscriptionService.renew).
        invoiceRepository.findOutstandingBySubscriptionId(subscription.id).firstOrNull()?.let {
            return it.toView(sandboxMode())
        }
        val invoice = invoiceGenerator.issueFor(subscription, LocalDate.now(), force = true)
            ?: throw NotFoundException("Tagihan tak dapat diterbitkan (langganan dibatalkan atau tagihan periode ini sudah ada)")
        return invoice.toView(sandboxMode())
    }

    @Transactional
    override fun voidInvoice(invoiceId: UUID): SubscriptionInvoiceView {
        val invoice = invoiceRepository.findById(invoiceId)
            ?: throw NotFoundException("Tagihan langganan tidak ditemukan")
        invoice.void()
        val saved = invoiceRepository.save(invoice)
        auditor.record(
            action = "platform.subscription.invoice.voided",
            entityType = "TenantSubscriptionInvoice",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("number" to saved.number),
        )
        return saved.toView(sandboxMode())
    }

    @Transactional
    override fun recordManualPayment(invoiceId: UUID, command: ManualPaymentCommand): SubscriptionInvoiceView =
        paymentService.recordManualPayment(invoiceId, command.amount, command.note).toView(sandboxMode())

    @Transactional
    override fun cancel(tenantId: UUID): TenantSubscriptionDetailView {
        val subscription = subscriptionRepository.findByTenantId(tenantId)
            ?: throw NotFoundException("Tenant belum berlangganan")
        subscription.cancel()
        val saved = subscriptionRepository.save(subscription)
        auditor.record(
            action = "platform.subscription.cancelled",
            entityType = "TenantSubscription",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("tenantId" to tenantId.toString()),
        )
        return saved.toDetailView()
    }

    private fun TenantSubscription.toDetailView(): TenantSubscriptionDetailView {
        val sandbox = sandboxMode()
        val invoices = invoiceRepository.findBySubscriptionId(id).map { it.toView(sandbox) }
        return TenantSubscriptionDetailView(
            tenantId = tenantId,
            monthlyFee = monthlyFee,
            status = status,
            billingDay = billingDay,
            graceDays = graceDays,
            currentPeriodStart = currentPeriodStart,
            currentPeriodEnd = currentPeriodEnd,
            nextInvoiceAt = nextInvoiceAt,
            activatedAt = activatedAt,
            invoices = invoices,
        )
    }

    /**
     * [sandbox] = Pivot master sedang mode sandbox; hanya saat itu id sesi bayar dibuka agar
     * super-admin bisa menyalinnya ke panel Simulasi Pembayaran.
     */
    private fun TenantSubscriptionInvoice.toView(sandbox: Boolean) = SubscriptionInvoiceView(
        id = id,
        tenantId = tenantId,
        number = number,
        periodStart = periodStart,
        periodEnd = periodEnd,
        amount = amount,
        status = status,
        issuedAt = issuedAt,
        dueDate = dueDate,
        paidAt = paidAt,
        gatewayProvider = gatewayProvider,
        payUrl = payUrl,
        simulatable = sandbox &&
            gatewayProvider.equals("PIVOT", ignoreCase = true) &&
            !gatewayRef.isNullOrBlank() &&
            isOutstanding,
        paymentSessionId = gatewayRef?.takeIf { sandbox },
    )

    private fun sandboxMode(): Boolean = masterConfig.current()?.sandbox == true
}
