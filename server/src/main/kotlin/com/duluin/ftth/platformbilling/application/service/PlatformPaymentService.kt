package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.application.port.outbound.PaymentSettlement
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.ReadOnlyLockGuard
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionPaymentRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionInvoice
import com.duluin.ftth.platformbilling.domain.model.TenantSubscriptionPayment
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Menerapkan pelunasan tagihan langganan (dari webhook gateway atau pencatatan manual super-admin)
 * lalu memulihkan tenant bila seluruh tunggakan lunas. Idempoten: callback gateway bisa datang
 * berkali-kali; tagihan yang sudah PAID tak diproses ulang.
 */
@Service
@Transactional
class PlatformPaymentService(
    private val invoiceRepository: TenantSubscriptionInvoiceRepository,
    private val paymentRepository: TenantSubscriptionPaymentRepository,
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
    private val lockGuard: ReadOnlyLockGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Terapkan settlement gateway. Tagihan tak dikenal / sudah lunas = no-op aman. */
    fun applySettlement(settlement: PaymentSettlement) {
        val invoice = invoiceRepository.findByNumber(settlement.invoiceNumber)
        if (invoice == null) {
            log.warn("Settlement untuk tagihan langganan tak dikenal: {}", settlement.invoiceNumber)
            return
        }
        applyPayment(
            invoice = invoice,
            amount = settlement.amount,
            provider = settlement.provider,
            gatewayRef = settlement.gatewayRef,
            paidAt = settlement.paidAt,
            note = null,
        )
    }

    /** Catat pembayaran manual super-admin (mis. transfer di luar gateway) atas sebuah tagihan. */
    fun recordManualPayment(invoiceId: UUID, amount: BigDecimal?, note: String?): TenantSubscriptionInvoice {
        val invoice = invoiceRepository.findById(invoiceId)
            ?: throw NotFoundException("Tagihan langganan tidak ditemukan")
        return applyPayment(
            invoice = invoice,
            amount = amount ?: invoice.amount,
            provider = "MANUAL",
            gatewayRef = null,
            paidAt = Instant.now(),
            note = note,
        )
    }

    /**
     * "Lunasi" tagihan bonus bulan gratis (Rp 0) — bukan pembayaran nyata, penyedianya `GRANT`.
     * Lewat jalur pelunasan yang sama supaya masa aktif bertambah, langganan & tenant yang sempat
     * tersuspend pulih, dan jejaknya tercatat persis seperti pelunasan biasa.
     */
    fun recordGrant(invoiceId: UUID, note: String?): TenantSubscriptionInvoice {
        val invoice = invoiceRepository.findById(invoiceId)
            ?: throw NotFoundException("Tagihan langganan tidak ditemukan")
        return applyPayment(
            invoice = invoice,
            amount = BigDecimal.ZERO,
            provider = "GRANT",
            gatewayRef = null,
            paidAt = Instant.now(),
            note = note,
        )
    }

    private fun applyPayment(
        invoice: TenantSubscriptionInvoice,
        amount: BigDecimal,
        provider: String,
        gatewayRef: String?,
        paidAt: Instant,
        note: String?,
    ): TenantSubscriptionInvoice {
        // Idempoten: tagihan yang sudah lunas tak dicatat ulang (callback berulang).
        if (invoice.status == com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus.PAID) {
            return invoice
        }
        invoice.markPaid(paidAt)
        val saved = invoiceRepository.save(invoice)
        paymentRepository.save(
            TenantSubscriptionPayment.create(
                tenantId = saved.tenantId,
                invoiceId = saved.id,
                amount = amount,
                provider = provider,
                gatewayRef = gatewayRef,
                paidAt = paidAt,
                note = note,
            ),
        )
        auditor.record(
            action = "platform.subscription.invoice.paid",
            entityType = "TenantSubscriptionInvoice",
            entityId = saved.id,
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf("number" to saved.number, "provider" to provider),
        )
        // Pelunasan memperpanjang masa aktif (bukan penerbitan tagihan) — sesuai model LUNAS.
        // Jumlah bulan diturunkan dari rentang periode tagihan (mendukung prabayar >1 bulan).
        subscriptionRepository.findById(saved.subscriptionId)?.let { subscription ->
            val months = ChronoUnit.MONTHS.between(saved.periodStart, saved.periodEnd.plusDays(1)).coerceAtLeast(1)
            subscription.extendOnPayment(LocalDate.now(), months)
            subscriptionRepository.save(subscription)
        }
        reactivateIfCleared(saved.subscriptionId, saved.tenantId)
        return saved
    }

    /**
     * Bila tak ada lagi tagihan tertunggak, pulihkan langganan ke ACTIVE — dan dengan itu kunci
     * baca-saja konsol tenant terbuka.
     *
     * Tenant-nya sendiri sengaja TIDAK ikut diaktifkan. Tenant yang berstatus SUSPENDED hari ini
     * hanya bisa sampai di sana lewat tangan platform admin (menunggak tak lagi men-suspend
     * tenant), dan melunasi tagihan bukan alasan untuk membatalkan keputusan itu.
     */
    private fun reactivateIfCleared(subscriptionId: UUID, tenantId: UUID) {
        if (invoiceRepository.findOutstandingBySubscriptionId(subscriptionId).isNotEmpty()) return
        val subscription = subscriptionRepository.findById(subscriptionId) ?: return
        if (subscription.status == SubscriptionStatus.ACTIVE || subscription.isCancelled) return

        subscription.activate()
        subscriptionRepository.save(subscription)
        // Tanpa ini, konsol baru terasa terbuka setelah cache penjaga kedaluwarsa sendiri —
        // dan orang yang baru saja membayar mengira pembayarannya gagal.
        lockGuard.invalidate(tenantId)
        auditor.record(
            action = "platform.subscription.tenant.unlocked",
            entityType = "Tenant",
            entityId = tenantId,
            tenantId = tenantApi.platformTenantId(),
        )
    }
}
