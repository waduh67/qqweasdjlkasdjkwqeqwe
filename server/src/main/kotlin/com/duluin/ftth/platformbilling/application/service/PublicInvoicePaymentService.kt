package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.BillingApi
import com.duluin.ftth.billing.PaymentMethodOption
import com.duluin.ftth.billing.PublicInvoiceRef
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.platformbilling.application.port.inbound.ByteArrayContent
import com.duluin.ftth.platformbilling.application.port.inbound.PublicInvoicePaymentUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.PublicInvoiceView
import com.duluin.ftth.platformbilling.application.port.inbound.PublicManualInstructionsView
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantRef
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Orkestrator halaman bayar publik. Hidup di `platformbilling` dengan alasan yang sama seperti
 * [com.duluin.ftth.platformbilling.adapter.inbound.web.PivotCallbackController]: di sinilah kedua
 * sisi tagihan bertemu, dan `platformbilling` yang bergantung ke `billing`, bukan sebaliknya.
 *
 * SENGAJA tidak `@Transactional` — persis alasan `PortalAuthenticationService`: tenant di-resolve
 * dari slug LEBIH DULU, [TenantContext] dipasang, baru worker transaksional dipanggil, agar session
 * Hibernate terbuka dengan tenant yang benar (tabel `invoice` ber-RLS FORCE; tanpa ini resolver
 * memulangkan sentinel ROOT dan tagihan tak akan pernah ketemu).
 *
 * Status tenant TIDAK ikut menyaring: tenant yang ditangguhkan justru yang paling perlu membayar,
 * dan pelanggannya tak boleh ikut kena getahnya.
 */
@Service
class PublicInvoicePaymentService(
    private val tenants: TenantApi,
    private val billing: BillingApi,
    private val subscriptions: PublicSubscriptionInvoices,
) : PublicInvoicePaymentUseCase {

    override fun find(tenantSlug: String, invoiceId: UUID): PublicInvoiceView {
        val tenant = requireTenant(tenantSlug)
        // Realm 1: tagihan pelanggan (ter-RLS). Realm 2: tagihan langganan SaaS (level platform).
        TenantContext.runAs(tenant.id) { billing.findInvoiceForPublicLink(invoiceId) }
            ?.let { return it.toView(tenant) }
        return subscriptions.find(invoiceId, tenant.id)?.toView(tenant) ?: throw notFound()
    }

    override fun pay(tenantSlug: String, invoiceId: UUID, method: String, channel: String?): PublicInvoiceView {
        val tenant = requireTenant(tenantSlug)
        // Realm ditentukan dari keberadaan tagihannya, bukan dari parameter — klien tak perlu tahu.
        val paid = TenantContext.runAs(tenant.id) {
            if (billing.findInvoiceForPublicLink(invoiceId) == null) null
            else billing.payInvoiceForPublicLink(invoiceId, method, channel)
        }
        if (paid != null) return paid.toView(tenant)
        if (subscriptions.find(invoiceId, tenant.id) == null) throw notFound()
        return subscriptions.pay(invoiceId, tenant.id, method, channel).toView(tenant)
    }

    override fun paymentMethods(): List<PaymentMethodOption> = billing.paymentMethods()

    override fun manualQrisImage(tenantSlug: String, invoiceId: UUID): ByteArrayContent? {
        val tenant = requireTenant(tenantSlug)
        return TenantContext.runAs(tenant.id) {
            // Tautan divalidasi dulu: gambar QRIS tenant tak boleh bisa dipanen hanya dari slug.
            billing.findInvoiceForPublicLink(invoiceId) ?: throw notFound()
            billing.manualQrisImage()?.let { ByteArrayContent(it.contentType, it.bytes) }
        }
    }

    private fun requireTenant(slug: String): TenantRef =
        tenants.findBySlug(slug.trim().lowercase()) ?: throw notFound()

    /**
     * SATU kalimat untuk semua sebab (slug asing, UUID asing, tagihan tenant lain) — pemegang
     * tautan yang menebak-nebak tak boleh bisa membedakan mana yang salah.
     */
    private fun notFound() = NotFoundException("Tagihan tidak ditemukan atau tautannya sudah tidak berlaku")

    private fun PublicInvoiceRef.toView(tenant: TenantRef) = PublicInvoiceView(
        id = id,
        number = number,
        tenantSlug = tenant.slug,
        tenantName = tenant.name,
        payerName = customerName,
        periodStart = periodStart,
        periodEnd = periodEnd,
        amount = amount,
        status = status,
        dueDate = dueDate,
        paidAt = paidAt,
        payableOnline = payableOnline,
        payMethod = payMethod,
        vaChannel = vaChannel,
        vaNumber = vaNumber,
        vaName = vaName,
        vaExpiresAt = vaExpiresAt,
        qrContent = qrContent,
        qrExpiresAt = qrExpiresAt,
        manual = manual?.let {
            PublicManualInstructionsView(
                transferEnabled = it.transferEnabled,
                bankName = it.bankName,
                accountNumber = it.accountNumber,
                accountHolder = it.accountHolder,
                qrisEnabled = it.qrisEnabled,
                qrisImageAvailable = it.qrisImageAvailable,
            )
        },
    )

    /** Langganan SaaS ditagih di akun master Pivot — tak ada jalur MANUAL, jadi [manual] selalu null. */
    private fun TenantSubscriptionInvoice.toView(tenant: TenantRef) = PublicInvoiceView(
        id = id,
        number = number,
        tenantSlug = tenant.slug,
        tenantName = tenant.name,
        payerName = tenant.name,
        periodStart = periodStart,
        periodEnd = periodEnd,
        amount = amount,
        status = status.name,
        dueDate = dueDate,
        paidAt = paidAt,
        payableOnline = subscriptions.payableOnline(this),
        payMethod = payMethod,
        vaChannel = vaChannel,
        vaNumber = vaNumber,
        vaName = vaName,
        vaExpiresAt = vaExpiresAt,
        qrContent = qrContent,
        qrExpiresAt = qrExpiresAt,
        manual = null,
    )
}
