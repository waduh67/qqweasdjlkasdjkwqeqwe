package com.duluin.ftth.portal.application.service

import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.CustomerInvoiceRef
import com.duluin.ftth.billing.CustomerPaymentRef
import com.duluin.ftth.bng.BngApi
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.CpeApi
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.helpdesk.HelpdeskApi
import com.duluin.ftth.helpdesk.SubmitTicketCommand
import com.duluin.ftth.portal.application.port.inbound.PortalAccountView
import com.duluin.ftth.portal.application.port.inbound.PortalBillingView
import com.duluin.ftth.portal.application.port.inbound.PortalConnectionView
import com.duluin.ftth.portal.application.port.inbound.PortalDeviceView
import com.duluin.ftth.portal.application.port.inbound.PortalInvoicePrintView
import com.duluin.ftth.portal.application.port.inbound.PortalInvoiceView
import com.duluin.ftth.portal.application.port.inbound.PortalPaymentMethodView
import com.duluin.ftth.portal.application.port.inbound.PortalPaymentView
import com.duluin.ftth.portal.application.port.inbound.PortalPlanChangeCommand
import com.duluin.ftth.portal.application.port.inbound.PortalPlanChangeReceiptView
import com.duluin.ftth.portal.application.port.inbound.PortalPlanOptionView
import com.duluin.ftth.portal.application.port.inbound.PortalSelfServiceUseCase
import com.duluin.ftth.portal.application.port.inbound.PortalVaChannelView
import com.duluin.ftth.portal.application.port.inbound.PortalSessionView
import com.duluin.ftth.portal.application.port.inbound.PortalSubscriptionView
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
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
@Suppress("LongParameterList")
class PortalSelfService(
    private val customerApi: CustomerApi,
    private val catalogApi: CatalogApi,
    private val billingApi: BillingApi,
    private val bngApi: BngApi,
    private val cpeApi: CpeApi,
    private val helpdeskApi: HelpdeskApi,
    private val tenantApi: TenantApi,
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
        // Riwayat pembayaran menyebut NOMOR tagihannya, bukan UUID: "Rp150.000 lewat QRIS" saja
        // tak menjawab pertanyaan yang sebenarnya dibawa pelanggan — "yang bulan mana?".
        val numbersById = invoices.associate { it.id to it.number }
        val payments = billingApi.findCustomerPayments(customerId).map { it.toPortalView(numbersById) }
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

    override fun invoiceForPrint(customerId: UUID, invoiceId: UUID): PortalInvoicePrintView {
        val customer = customerApi.findCustomer(customerId)
            ?: throw NotFoundException("Pelanggan tidak ditemukan")
        val detail = billingApi.findCustomerInvoiceDetail(customerId, invoiceId)
            ?: throw NotFoundException("Tagihan tidak ditemukan")
        val invoice = detail.invoice.toPortalView()
        // Nama paket diambil dari langganan yang ditagih — lembar cetak menyebut APA yang dibayar,
        // bukan sekadar "tagihan periode sekian".
        val packageName = customerApi.findSubscription(detail.subscriptionId)?.packageName
        return PortalInvoicePrintView(
            issuerName = tenantApi.requireById(TenantContext.tenantId()).name,
            customerName = customer.name,
            customerCode = customer.code,
            packageName = packageName,
            invoice = invoice,
            baseAmount = detail.baseAmount,
            taxAmount = detail.taxAmount,
            taxRate = detail.taxRate,
            prorated = detail.prorated,
            proratedDays = detail.proratedDays,
            payments = detail.payments.map { it.toPortalView(mapOf(invoice.id to invoice.number)) },
        )
    }

    override fun planOptions(customerId: UUID): List<PortalPlanOptionView> {
        val currentPlanIds = customerApi.findSubscriptionsByCustomer(customerId).mapNotNull { it.planId }.toSet()
        return catalogApi.findActivePlans().map { plan ->
            val network = catalogApi.findPlanNetwork(plan.planId)
            PortalPlanOptionView(
                planId = plan.planId,
                name = plan.packageName,
                monthlyFee = plan.monthlyFee,
                bandwidthMbps = plan.bandwidthMbps,
                downMbps = network?.downMbps,
                upMbps = network?.upMbps,
                fupEnabled = network?.fupEnabled ?: false,
                fupQuotaMb = network?.fupQuotaMb,
                current = plan.planId in currentPlanIds,
            )
        }
    }

    @Transactional
    override fun requestPlanChange(
        customerId: UUID,
        command: PortalPlanChangeCommand,
    ): PortalPlanChangeReceiptView {
        // Langganan wajib milik pelanggan yang login — bukan sekadar "ada di tenant ini".
        val subscription = customerApi.findSubscriptionsByCustomer(customerId)
            .firstOrNull { it.id == command.subscriptionId }
            ?: throw NotFoundException("Langganan tidak ditemukan")
        val target = catalogApi.findPlanCommercial(command.targetPlanId)?.takeIf { it.active }
            ?: throw ValidationException("Paket yang dipilih tidak tersedia")
        if (subscription.planId == target.planId) {
            throw ValidationException("Paket ini sudah kamu pakai sekarang")
        }
        val subject = "Ajuan ganti paket: ${subscription.packageName} → ${target.packageName}"
        val ticket = helpdeskApi.submit(
            SubmitTicketCommand(
                customerId = customerId,
                category = PLAN_CHANGE_CATEGORY,
                // Subjek tiket dibatasi 150 karakter; nama paket panjang tak boleh menggagalkan ajuan.
                subject = subject.take(MAX_SUBJECT),
                description = describePlanChange(subscription.packageName, target.packageName, target.monthlyFee, command.note),
            ),
        ).ticket
        return PortalPlanChangeReceiptView(
            ticketId = ticket.id,
            ticketCode = ticket.code,
            subject = ticket.subject,
            status = ticket.status,
        )
    }

    /**
     * Badan tiket ajuan. Ditulis SERVER, bukan disalin dari klien: operator harus bisa
     * mempercayai bahwa "paket sekarang" dan "harga paket tujuan" di ajuan memang yang
     * tercatat sistem, bukan angka yang diketik pelanggan.
     */
    private fun describePlanChange(from: String, to: String, fee: BigDecimal, note: String?): String {
        val body = buildString {
            append("Pelanggan mengajukan pindah paket.\n")
            append("Paket sekarang: $from\n")
            append("Paket diminta: $to (Rp${fee.toPlainString()}/bulan)\n")
            if (!note.isNullOrBlank()) append("\nCatatan pelanggan:\n${note.trim()}")
        }
        return body.take(MAX_DESCRIPTION)
    }

    private fun CustomerPaymentRef.toPortalView(invoiceNumbers: Map<UUID, String>) = PortalPaymentView(
        id = id,
        invoiceId = invoiceId,
        invoiceNumber = invoiceNumbers[invoiceId],
        amount = amount,
        provider = provider,
        paidAt = paidAt,
        note = note,
    )

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

    private companion object {
        /** Nama `TicketCategory` helpdesk — enum tak menyeberang batas modul, jadi dikirim sebagai String. */
        const val PLAN_CHANGE_CATEGORY = "GANTI_PAKET"
        const val MAX_SUBJECT = 150
        const val MAX_DESCRIPTION = 2000
    }
}
