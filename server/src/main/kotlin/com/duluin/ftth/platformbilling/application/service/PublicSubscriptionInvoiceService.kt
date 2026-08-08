package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.PaymentMethodCatalog
import com.duluin.ftth.billing.StoredInstruction
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Realm kedua halaman bayar publik (tagihan langganan SaaS), sebagai port sempit — sejalan dengan
 * `ActiveGatewayProbe`/`InvoiceChargePort` di module `billing`: [PublicInvoicePaymentService] cuma
 * butuh tiga operasi ini, sementara implementasinya menyeret generator & resolver gateway platform
 * yang mustahil difake di unit test.
 */
interface PublicSubscriptionInvoices {
    /** Tagihan langganan milik [tenantId]; null bila tak ada atau milik tenant lain. */
    fun find(invoiceId: UUID, tenantId: UUID): TenantSubscriptionInvoice?

    fun pay(invoiceId: UUID, tenantId: UUID, method: String, channel: String?): TenantSubscriptionInvoice

    fun payableOnline(invoice: TenantSubscriptionInvoice): Boolean
}

/**
 * Sisi tagihan LANGGANAN SaaS untuk halaman bayar publik — pasangan
 * `BillingApi.findInvoiceForPublicLink`/`payInvoiceForPublicLink` di module `billing`, dipakai
 * [PublicInvoicePaymentService] sebagai realm kedua.
 *
 * Tanpa penyaringan pemilik lewat login: kapabilitasnya adalah UUID tagihan itu sendiri. Tabel
 * `tenant_subscription_invoice` level platform (TANPA RLS), jadi kepemilikan tenant dicek eksplisit
 * di sini — bandingkan `TenantSelfSubscriptionService.payInvoice` yang mengecek lewat langganan
 * tenant pada [com.duluin.ftth.common.tenant.TenantContext].
 */
@Service
@Transactional(readOnly = true)
class PublicSubscriptionInvoiceService(
    private val invoiceRepository: TenantSubscriptionInvoiceRepository,
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val invoiceGenerator: PlatformInvoiceGenerator,
    private val gatewayResolver: PlatformGatewayResolver,
) : PublicSubscriptionInvoices {
    override fun find(invoiceId: UUID, tenantId: UUID): TenantSubscriptionInvoice? =
        invoiceRepository.findById(invoiceId)?.takeIf { it.tenantId == tenantId }

    /**
     * Buat charge in-app untuk tagihan langganan milik [tenantId]. Instruksi hidup yang cocok
     * dipakai ulang (lihat [PaymentMethodCatalog.stillUsable]) supaya memuat ulang tautan publik
     * tak menghambur sesi bayar baru di penyedia.
     */
    @Transactional
    override fun pay(invoiceId: UUID, tenantId: UUID, method: String, channel: String?): TenantSubscriptionInvoice {
        val invoice = find(invoiceId, tenantId) ?: throw NotFoundException("Tagihan tidak ditemukan")
        if (!invoice.isOutstanding) {
            throw ValidationException("Tagihan ini tidak dapat dibayar (status ${invoice.status}).")
        }
        PaymentMethodCatalog.validate(method, channel)
        if (PaymentMethodCatalog.stillUsable(invoice.storedInstruction(), method, channel, Instant.now())) {
            return invoice
        }
        val subscription = subscriptionRepository.findById(invoice.subscriptionId)
            ?: throw NotFoundException("Tagihan tidak ditemukan")
        return invoiceGenerator.chargeWithMethod(invoice, subscription, method, channel)
    }

    /**
     * Bayar online tersedia untuk tagihan langganan: master Pivot platform aktif. Tak seperti sisi
     * tenant, di sini tak ada fallback MANUAL — langganan SaaS hanya ditagih lewat master Pivot.
     */
    override fun payableOnline(invoice: TenantSubscriptionInvoice): Boolean =
        invoice.isOutstanding && gatewayResolver.resolve("PIVOT") != null

    private fun TenantSubscriptionInvoice.storedInstruction() =
        StoredInstruction(payMethod, vaChannel, vaNumber, vaExpiresAt, qrContent, qrExpiresAt)
}
