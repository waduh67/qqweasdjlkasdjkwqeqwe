package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.platformbilling.application.port.inbound.SubscriptionInvoiceView
import com.duluin.ftth.platformbilling.application.port.inbound.TenantSelfSubscriptionUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.TenantSelfSubscriptionView
import com.duluin.ftth.platformbilling.application.port.inbound.UsageMetricView
import com.duluin.ftth.platformbilling.application.port.outbound.SubscriptionUsageProbe
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.TenantSubscription
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Layanan langganan sisi tenant (self-service). Selalu bekerja pada tenant konteks berjalan
 * ([TenantContext]) — tenant admin hanya bisa melihat/memperpanjang langganannya sendiri.
 * Penerbitan tagihan didelegasikan ke [PlatformInvoiceGenerator] agar seragam dgn jalur admin/scheduler.
 */
@Service
@Transactional(readOnly = true)
class TenantSelfSubscriptionService(
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val invoiceRepository: TenantSubscriptionInvoiceRepository,
    private val invoiceGenerator: PlatformInvoiceGenerator,
    private val usageProbe: SubscriptionUsageProbe,
    private val masterConfig: PivotMasterConfigProvider,
) : TenantSelfSubscriptionUseCase {

    override fun current(): TenantSelfSubscriptionView? {
        val subscription = subscriptionRepository.findByTenantId(TenantContext.tenantId()) ?: return null
        return subscription.toSelfView()
    }

    @Transactional
    override fun renew(months: Int): SubscriptionInvoiceView {
        if (months !in 1..MAX_PREPAY_MONTHS) {
            throw ValidationException("Jumlah bulan harus 1..$MAX_PREPAY_MONTHS")
        }
        val subscription = subscriptionRepository.findByTenantId(TenantContext.tenantId())
            ?: throw NotFoundException("Tenant belum berlangganan")
        if (subscription.isCancelled) {
            throw ValidationException("Langganan dibatalkan — hubungi admin platform")
        }
        // Bila sudah ada tagihan tertunggak, kembalikan yang itu (hindari terbit ganda). Charge
        // TIDAK dibuat di sini — instrumen bayar (VA/QRIS) dipilih tenant lewat "Bayar" → payInvoice.
        invoiceRepository.findOutstandingBySubscriptionId(subscription.id).firstOrNull()?.let {
            return it.toView(sandboxMode())
        }
        val invoice = invoiceGenerator.issueFor(subscription, LocalDate.now(), force = true, months = months)
            ?: throw ValidationException(
                "Periode langganan ini sudah dibayar — belum ada tagihan baru untuk diperpanjang.",
            )
        return invoice.toView(sandboxMode())
    }

    @Transactional
    override fun payInvoice(invoiceId: UUID, method: String, channel: String?): SubscriptionInvoiceView {
        val subscription = subscriptionRepository.findByTenantId(TenantContext.tenantId())
            ?: throw NotFoundException("Tenant belum berlangganan")
        // Batasi ke tagihan milik langganan tenant ini — tenant tak boleh membayar tagihan tenant lain.
        val invoice = invoiceRepository.findById(invoiceId)
            ?.takeIf { it.subscriptionId == subscription.id }
            ?: throw NotFoundException("Tagihan tidak ditemukan")
        if (!invoice.isOutstanding) {
            throw ValidationException("Tagihan ini tidak dapat dibayar (status ${invoice.status}).")
        }
        return invoiceGenerator.chargeWithMethod(invoice, subscription, method, channel).toView(sandboxMode())
    }

    @Transactional
    override fun simulateInvoicePayment(invoiceId: UUID, status: SimulatedChargeStatus): SubscriptionInvoiceView {
        val subscription = subscriptionRepository.findByTenantId(TenantContext.tenantId())
            ?: throw NotFoundException("Tenant belum berlangganan")
        // Sama seperti payInvoice: batasi ke tagihan milik langganan tenant ini.
        val invoice = invoiceRepository.findById(invoiceId)
            ?.takeIf { it.subscriptionId == subscription.id }
            ?: throw NotFoundException("Tagihan tidak ditemukan")
        if (!invoice.isOutstanding) {
            throw ValidationException("Tagihan ini tidak dapat disimulasikan (status ${invoice.status}).")
        }
        invoiceGenerator.simulatePayment(invoice, status)
        // Status belum berubah di sini — pelunasan menyusul lewat callback penyedia.
        return invoice.toView(sandboxMode())
    }

    /** Pivot master sedang mode sandbox? Penentu apakah simulasi pembayaran boleh ditawarkan. */
    private fun sandboxMode(): Boolean = masterConfig.current()?.sandbox == true

    private fun TenantSubscription.toSelfView(): TenantSelfSubscriptionView {
        // Flag sandbox dibaca SEKALI per query, bukan per baris — setelan master global.
        val sandbox = sandboxMode()
        val invoices = invoiceRepository.findBySubscriptionId(id).map { it.toView(sandbox) }
        val usage = usageProbe.currentTenantUsage().map {
            // Semua kuota "Unlimited" (limit null) — pemakaian bersifat kosmetik.
            UsageMetricView(key = it.key, label = it.label, used = it.used, limit = null)
        }
        return TenantSelfSubscriptionView(
            status = status,
            monthlyFee = monthlyFee,
            activeUntil = currentPeriodEnd,
            currentPeriodStart = currentPeriodStart,
            nextInvoiceAt = nextInvoiceAt,
            usage = usage,
            invoices = invoices,
        )
    }

    private companion object {
        /** Batas atas bayar di muka (bulan) — cukup lebar (setahun) tanpa membuka penyalahgunaan. */
        const val MAX_PREPAY_MONTHS = 12
    }

    /**
     * [sandbox] = Pivot master sedang mode sandbox; hanya saat itu [SubscriptionInvoiceView.simulatable]
     * bisa menyala (tagihan Pivot yang sudah punya sesi bayar & masih tertunggak).
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
        grant = isGrant,
        payMethod = payMethod,
        vaChannel = vaChannel,
        vaNumber = vaNumber,
        vaName = vaName,
        vaExpiresAt = vaExpiresAt,
        qrContent = qrContent,
        qrUrl = qrUrl,
        qrExpiresAt = qrExpiresAt,
        simulatable = sandbox &&
            gatewayProvider.equals("PIVOT", ignoreCase = true) &&
            !gatewayRef.isNullOrBlank() &&
            isOutstanding,
        paymentSessionId = gatewayRef?.takeIf { sandbox },
    )
}
