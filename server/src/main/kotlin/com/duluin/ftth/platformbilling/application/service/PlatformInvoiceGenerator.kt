package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.application.port.outbound.ChargeRequest
import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.TenantSubscription
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Mesin penerbitan tagihan langganan tenant, dipakai bersama trigger manual (service) dan
 * scheduler platform. Untuk tiap langganan yang jatuh tempo: buat tagihan periode berikutnya,
 * charge ke gateway AKTIF (dari [PlatformGatewayResolver] — sumber kebenaran, bukan config lama),
 * lekatkan tautan bayar, lalu majukan `next_invoice_at`.
 *
 * Anti-duplikat: nomor `SUB-<yyyymm>-<tenant8>` unik per periode+tenant; bila tagihan periode ini
 * sudah ada, langganan dilewati (dan `next_invoice_at` tetap dimajukan agar tak tersangkut).
 */
@Component
class PlatformInvoiceGenerator(
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val invoiceRepository: TenantSubscriptionInvoiceRepository,
    private val resolver: PlatformGatewayResolver,
    private val gatewayRegistry: PaymentGatewayRegistry,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Terbitkan tagihan periode berjalan untuk [subscription] bila jatuh temponya sudah tiba
     * ([TenantSubscription.nextInvoiceAt] <= [today]) atau [force] (trigger manual super-admin).
     * Mengembalikan tagihan yang dibuat, atau null bila dilewati.
     */
    fun issueFor(subscription: TenantSubscription, today: LocalDate, force: Boolean = false): TenantSubscriptionInvoice? {
        if (subscription.isCancelled) return null
        val due = subscription.nextInvoiceAt
        if (!force && (due == null || due.isAfter(today))) return null

        val setting = resolver.setting()
        val periodStart = due ?: today
        val periodEnd = periodStart.plusMonths(1).minusDays(1)
        val number = "SUB-${periodStart.format(YEAR_MONTH)}-${tenantShort(subscription.tenantId)}"

        // Selalu majukan jadwal berikut agar langganan tak tersangkut, walau tagihan periode ini
        // sudah ada (penerbitan susulan/duplikat).
        subscription.openPeriod(periodStart, periodEnd, periodStart.plusMonths(1))
        subscriptionRepository.save(subscription)

        if (invoiceRepository.findByNumber(number) != null) {
            log.info("Tagihan langganan {} sudah ada — dilewati", number)
            return null
        }

        val dueDate = today.plusDays(setting.defaultDueDays.toLong())
        var invoice = TenantSubscriptionInvoice.create(
            tenantId = subscription.tenantId,
            subscriptionId = subscription.id,
            number = number,
            periodStart = periodStart,
            periodEnd = periodEnd,
            amount = subscription.monthlyFee,
            dueDate = dueDate,
        )
        invoice = invoiceRepository.save(invoice)

        // Charge ke gateway aktif; kegagalan TIDAK membatalkan tagihan (bisa di-charge ulang).
        runCatching { attachCharge(invoice, subscription) }
            .onFailure { log.warn("Charge gateway untuk tagihan {} gagal: {}", number, it.message) }
            .getOrNull()
            ?.let { invoice = it }

        auditor.record(
            action = "platform.subscription.invoice.issued",
            entityType = "TenantSubscriptionInvoice",
            entityId = invoice.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("number" to number, "tenantId" to subscription.tenantId.toString()),
        )
        return invoice
    }

    private fun attachCharge(
        invoice: TenantSubscriptionInvoice,
        subscription: TenantSubscription,
    ): TenantSubscriptionInvoice {
        val ctx = resolver.resolveActive()
        val gateway = gatewayRegistry.forProvider(ctx.provider)
            ?: error("Adapter gateway '${ctx.provider}' tidak tersedia")
        val tenant = tenantApi.requireById(subscription.tenantId)
        val result = gateway.createCharge(
            ChargeRequest(
                invoiceNumber = invoice.number,
                amount = invoice.amount,
                customerName = tenant.name,
                customerEmail = null,
                description = "Langganan aplikasi ${tenant.name} — ${invoice.periodStart.format(YEAR_MONTH)}",
            ),
            ctx,
        )
        invoice.attachCharge(result.provider, result.gatewayRef, result.payUrl)
        return invoiceRepository.save(invoice)
    }

    private companion object {
        val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
        fun tenantShort(tenantId: java.util.UUID): String =
            tenantId.toString().replace("-", "").take(8)
    }
}
