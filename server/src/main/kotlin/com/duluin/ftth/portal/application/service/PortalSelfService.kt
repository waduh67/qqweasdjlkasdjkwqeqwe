package com.duluin.ftth.portal.application.service

import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.CustomerInvoiceRef
import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.portal.application.port.inbound.PortalAccountView
import com.duluin.ftth.portal.application.port.inbound.PortalBillingView
import com.duluin.ftth.portal.application.port.inbound.PortalConnectionView
import com.duluin.ftth.portal.application.port.inbound.PortalDeviceView
import com.duluin.ftth.portal.application.port.inbound.PortalInvoiceView
import com.duluin.ftth.portal.application.port.inbound.PortalPaymentMethodView
import com.duluin.ftth.portal.application.port.inbound.PortalPaymentView
import com.duluin.ftth.portal.application.port.inbound.PortalSelfServiceUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalVaChannelView
import com.duluin.ftth.portal.application.port.inbound.PortalSessionView
import com.duluin.ftth.portal.application.port.inbound.PortalSubscriptionView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Model-baca self-service portal — merangkai kontrak PUBLIK modul lain (customer, catalog,
 * billing, bng, cpe) menjadi tampilan siap-saji untuk pelanggan. Persis pola `ReportService`
 * dan `Subscriber360Service`: `@Transactional(readOnly=true)`, tak menyentuh tabel modul lain.
 *
 * KEAMANAN: setiap metode menerima [customerId] dari controller (principal portal yang login),
 * BUKAN dari input klien — pelanggan hanya bisa membaca datanya sendiri. RLS tenant tetap aktif
 * di bawah (repo tenant-aware), jadi baca ter-scope ke tenant pelanggan.
 */
@Service
@Transactional(readOnly = true)
class PortalSelfService(
    private val customerApi: CustomerApi,
    private val catalogApi: CatalogApi,
    private val billingApi: BillingApi,
    private val bngApi: BngApi,
    private val cpeApi: CpeApi,
) : PortalSelfServiceUseCase {

    override fun profile(customerId: UUID): PortalAccountView {
        val customer = customerApi.findCustomer(customerId)
            ?: throw NotFoundException("Pelanggan tidak ditemukan")
        val subscriptions = customerApi.findSubscriptionsByCustomer(customerId).map { sub ->
            // Detail paket best-effort: paket mungkin sudah dinonaktifkan/terhapus (planId null).
            val commercial = sub.planId?.let { catalogApi.findPlanCommercial(it) }
            val network = sub.planId?.let { catalogApi.findPlanNetwork(it) }
            PortalSubscriptionView(
                subscriptionId = sub.id,
                packageName = sub.packageName,
                bandwidthMbps = sub.bandwidthMbps,
                status = sub.status,
                monthlyFee = commercial?.monthlyFee,
                downMbps = network?.downMbps,
                upMbps = network?.upMbps,
                fupEnabled = network?.fupEnabled ?: false,
                fupQuotaMb = network?.fupQuotaMb,
            )
        }
        return PortalAccountView(
            customerId = customer.id,
            code = customer.code,
            name = customer.name,
            phone = customer.phone,
            status = customer.status,
            subscriptions = subscriptions,
        )
    }

    override fun billing(customerId: UUID): PortalBillingView {
        val summary = billingApi.findAccountSummary(customerId)
        val invoices = billingApi.findCustomerInvoices(customerId).map { it.toPortalView() }
        val payments = billingApi.findCustomerPayments(customerId).map { pay ->
            PortalPaymentView(
                id = pay.id,
                invoiceId = pay.invoiceId,
                amount = pay.amount,
                provider = pay.provider,
                paidAt = pay.paidAt,
                note = pay.note,
            )
        }
        return PortalBillingView(
            outstandingAmount = summary.outstandingAmount,
            outstandingCount = summary.outstandingCount,
            unpaidCount = summary.unpaidCount,
            oldestDueDate = summary.oldestDueDate,
            lastPaidAt = summary.lastPaidAt,
            invoices = invoices,
            payments = payments,
        )
    }

    override fun paymentMethods(customerId: UUID): List<PortalPaymentMethodView> =
        billingApi.paymentMethods().map { m ->
            PortalPaymentMethodView(
                type = m.type,
                label = m.label,
                channels = m.channels.map { PortalVaChannelView(it.code, it.label) },
            )
        }

    @Transactional
    override fun payInvoice(
        customerId: UUID,
        invoiceId: UUID,
        method: String,
        channel: String?,
    ): PortalInvoiceView =
        billingApi.payCustomerInvoice(customerId, invoiceId, method, channel).toPortalView()

    override fun connection(customerId: UUID): PortalConnectionView {
        val session = bngApi.findSubscriberSession(customerId)?.let {
            PortalSessionView(
                username = it.username,
                accessStatus = it.accessStatus,
                planName = it.rateProfileName,
                online = it.online,
                framedIp = it.framedIp,
                nasName = it.nasName,
                uptimeSeconds = it.uptimeSeconds,
                startedAt = it.startedAt,
                lastSeenAt = it.lastSeenAt,
            )
        }
        val devices = cpeApi.findDevicesForCustomer(customerId).map {
            PortalDeviceView(
                deviceId = it.deviceId,
                serialNumber = it.serialNumber,
                manufacturer = it.manufacturer,
                model = it.model,
                softwareVersion = it.softwareVersion,
                ipAddress = it.ipAddress,
                online = it.online,
                lastInformAt = it.lastInformAt,
            )
        }
        return PortalConnectionView(session, devices)
    }

    /**
     * Petakan referensi tagihan billing → pandangan portal. [PortalInvoiceView.payable] = tagihan
     * masih terbuka (ISSUED/OVERDUE); pelanggan memilih instrumen (VA/QRIS) lewat "Bayar" yang
     * lalu memanggil [payInvoice]. Instruksi bayar tersimpan diteruskan agar panel bisa dirender ulang.
     */
    private fun CustomerInvoiceRef.toPortalView(): PortalInvoiceView {
        val open = status == "ISSUED" || status == "OVERDUE"
        return PortalInvoiceView(
            id = id,
            number = number,
            periodStart = periodStart,
            periodEnd = periodEnd,
            amount = amount,
            status = status,
            issuedAt = issuedAt,
            dueDate = dueDate,
            paidAt = paidAt,
            payable = open,
            payUrl = if (open) payUrl else null,
            payMethod = payMethod,
            vaChannel = vaChannel,
            vaNumber = vaNumber,
            vaName = vaName,
            vaExpiresAt = vaExpiresAt,
            qrContent = qrContent,
            qrUrl = qrUrl,
            qrExpiresAt = qrExpiresAt,
        )
    }
}
