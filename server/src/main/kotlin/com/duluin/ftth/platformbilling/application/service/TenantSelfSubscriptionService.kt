package com.duluin.ftth.platformbilling.application.service

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
        // Bila sudah ada tagihan tertunggak, lanjutkan bayar yang itu (hindari terbit ganda).
        invoiceRepository.findOutstandingBySubscriptionId(subscription.id).firstOrNull()?.let {
            return it.toView()
        }
        val invoice = invoiceGenerator.issueFor(subscription, LocalDate.now(), force = true, months = months)
            ?: throw ValidationException("Tagihan tak dapat diterbitkan saat ini")
        return invoice.toView()
    }

    private fun TenantSubscription.toSelfView(): TenantSelfSubscriptionView {
        val invoices = invoiceRepository.findBySubscriptionId(id).map { it.toView() }
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

    private fun TenantSubscriptionInvoice.toView() = SubscriptionInvoiceView(
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
    )
}
