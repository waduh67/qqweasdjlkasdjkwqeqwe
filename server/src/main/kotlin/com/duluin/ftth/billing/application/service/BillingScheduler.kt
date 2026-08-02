package com.duluin.ftth.billing.application.service

import com.duluin.ftth.billing.InvoiceDueSoon
import com.duluin.ftth.billing.InvoiceOverdue
import com.duluin.ftth.billing.application.port.outbound.InvoiceRepository
import com.duluin.ftth.billing.config.BillingProperties
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Menjalankan siklus penagihan lintas tenant secara berkala: menerbitkan tagihan
 * periode berjalan dan menegakkan tunggakan (auto-isolir). Berjalan di luar konteks
 * request, jadi tenant dipasang satu per satu lewat [TenantContext.runAs] — sama
 * seperti scheduler CPE. Kegagalan satu tenant tidak menghentikan tenant lain.
 */
@Component
class BillingScheduler(
    private val tenantApi: TenantApi,
    private val worker: BillingCycleRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.billing.scheduler-interval:PT12H}")
    fun issueInvoices() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { worker.issue(tenantId) } }
                .onFailure { log.warn("Penerbitan tagihan tenant {} gagal: {}", tenantId, it.message) }
        }
    }

    @Scheduled(fixedDelayString = "\${ftth.billing.scheduler-interval:PT12H}")
    fun enforceOverdue() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { worker.enforce(tenantId) } }
                .onFailure { log.warn("Penegakan tunggakan tenant {} gagal: {}", tenantId, it.message) }
        }
    }

    @Scheduled(fixedDelayString = "\${ftth.billing.scheduler-interval:PT12H}")
    fun remindDueSoon() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { worker.remindDueSoon(tenantId) } }
                .onFailure { log.warn("Pengingat jatuh tempo tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Pekerja satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [BillingScheduler], bukan method privat: `@Transactional`
 * Spring berlaku lewat proxy, jadi pemanggilan dari dalam kelas yang sama tak akan
 * pernah dibungkus transaksi. REQUIRES_NEW mengurung kegagalan ke satu tenant.
 */
@Component
class BillingCycleRunner(
    private val invoiceGenerator: InvoiceGenerator,
    private val invoiceRepository: InvoiceRepository,
    private val customerApi: CustomerApi,
    private val auditor: AuditRecorder,
    private val properties: BillingProperties,
    private val events: ApplicationEventPublisher,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun issue(tenantId: UUID) {
        // Gating tanggal penagihan kini per-langganan di dalam generator (paket bisa
        // menimpa billingDayOfMonth), jadi runner cukup memanggil untuk hari ini.
        invoiceGenerator.generateFor(tenantId, LocalDate.now())
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun enforce(tenantId: UUID) {
        val today = LocalDate.now()
        // Ambil superset kandidat (jatuh tempo < hari ini = grace 0), lalu saring per
        // langganan menurut grace efektifnya — paket bisa menimpa graceDays/autoIsolir
        // global (null = ikut global). Langganan tak ditemukan (mis. sudah berakhir)
        // memakai kebijakan global; isolir atas langganan non-aktif tetap no-op.
        invoiceRepository.findBillableOverdue(today).forEach { invoice ->
            val sub = customerApi.findBillableSubscription(invoice.subscriptionId)
            val graceDays = sub?.graceDays?.toLong() ?: properties.graceDays
            if (!invoice.dueDate.isBefore(today.minusDays(graceDays))) return@forEach

            invoice.markOverdue()
            val saved = invoiceRepository.save(invoice)
            auditor.record(
                "billing.invoice.overdue", "Invoice", saved.id, saved.tenantId,
                mapOf("number" to saved.number),
            )
            // Beri tahu pelanggan tagihannya menunggak (notification memutuskan kirim/tidak
            // lewat saklar pemicu). Sekali per tagihan: begitu OVERDUE ia tak terpilih lagi.
            events.publishEvent(
                InvoiceOverdue(saved.tenantId, saved.id, saved.customerId, saved.number, saved.amount, saved.dueDate),
            )
            val autoIsolir = sub?.autoIsolir ?: properties.autoIsolir
            if (autoIsolir) customerApi.isolateForBilling(saved.subscriptionId)
        }
    }

    /**
     * Ingatkan pelanggan atas tagihan yang jatuh tempo dalam [BillingProperties.reminderDaysBefore]
     * hari ke depan dan belum pernah diingatkan. Penanda `dueSoonReminded` dinyalakan di transaksi
     * yang sama dengan penerbitan event, jadi sweep berkala tak mengirimi ulang — bahkan bila tenant
     * sedang mematikan saklar pemicunya (menyalakannya kelak tak membanjiri pengingat lama).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun remindDueSoon(tenantId: UUID) {
        val today = LocalDate.now()
        val until = today.plusDays(properties.reminderDaysBefore)
        invoiceRepository.findRemindableDueSoon(today, until).forEach { invoice ->
            invoice.markDueSoonReminded()
            val saved = invoiceRepository.save(invoice)
            events.publishEvent(
                InvoiceDueSoon(saved.tenantId, saved.id, saved.customerId, saved.number, saved.amount, saved.dueDate),
            )
        }
    }
}
