package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionInvoiceRepository
import com.duluin.ftth.platformbilling.application.port.outbound.TenantSubscriptionRepository
import com.duluin.ftth.platformbilling.domain.model.SubscriptionInvoiceStatus
import com.duluin.ftth.tenancy.TenantApi
import com.duluin.ftth.tenancy.TenantStatus
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Menjalankan siklus penagihan langganan SaaS lintas tenant secara berkala: menerbitkan tagihan
 * periode berjalan dan menegakkan tunggakan (auto-suspend tenant setelah masa tenggang). Level
 * PLATFORM (tabel tanpa RLS) → TIDAK memakai `TenantContext.runAs`, beda dari `BillingScheduler`
 * tenant. Kegagalan satu langganan tak menghentikan yang lain.
 */
@Component
class PlatformBillingScheduler(
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val tenantApi: TenantApi,
    private val worker: PlatformBillingRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.platform-billing.scheduler-interval:PT12H}")
    fun issueInvoices() {
        subscriptionRepository.findDueForInvoice(LocalDate.now()).forEach { subscription ->
            runCatching { worker.issue(subscription.id) }
                .onFailure { log.warn("Penerbitan tagihan langganan {} gagal: {}", subscription.tenantId, it.message) }
        }
    }

    @Scheduled(fixedDelayString = "\${ftth.platform-billing.scheduler-interval:PT12H}")
    fun enforceOverdue() {
        // Sweep semua tenant aktif; enforce hanya berdampak bila tenant punya langganan menunggak.
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { worker.enforce(tenantId) }
                .onFailure { log.warn("Penegakan tunggakan langganan tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Pekerja satu langganan dalam transaksinya sendiri. Komponen terpisah dari [PlatformBillingScheduler]
 * (bukan method privat): `@Transactional` Spring hanya berlaku lewat proxy. REQUIRES_NEW mengurung
 * kegagalan ke satu langganan.
 */
@Component
class PlatformBillingRunner(
    private val subscriptionRepository: TenantSubscriptionRepository,
    private val invoiceRepository: TenantSubscriptionInvoiceRepository,
    private val invoiceGenerator: PlatformInvoiceGenerator,
    private val resolver: PlatformGatewayResolver,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun issue(subscriptionId: UUID) {
        val subscription = subscriptionRepository.findById(subscriptionId) ?: return
        invoiceGenerator.issueFor(subscription, LocalDate.now())
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun enforce(tenantId: UUID) {
        val subscription = subscriptionRepository.findByTenantId(tenantId) ?: return
        if (subscription.isCancelled) return

        val today = LocalDate.now()
        val graceDays = (subscription.graceDays ?: resolver.setting().defaultGraceDays).toLong()
        val outstanding = invoiceRepository.findOutstandingBySubscriptionId(subscription.id)
        if (outstanding.isEmpty()) return

        // 1) Tagihan ISSUED yang lewat jatuh tempo → OVERDUE, dan langganan → PAST_DUE.
        outstanding
            .filter { it.status == SubscriptionInvoiceStatus.ISSUED && it.dueDate.isBefore(today) }
            .forEach { invoice ->
                invoice.markOverdue()
                val saved = invoiceRepository.save(invoice)
                subscription.markPastDue()
                auditor.record(
                    action = "platform.subscription.invoice.overdue",
                    entityType = "TenantSubscriptionInvoice",
                    entityId = saved.id,
                    tenantId = tenantApi.platformTenantId(),
                    detail = mapOf("number" to saved.number),
                )
            }

        // 2) Ada tagihan menunggak melewati masa tenggang → suspend tenant + langganan.
        val pastGrace = invoiceRepository.findOutstandingBySubscriptionId(subscription.id)
            .any { it.dueDate.isBefore(today.minusDays(graceDays)) }
        if (pastGrace && subscription.status != com.duluin.ftth.platformbilling.domain.model.SubscriptionStatus.SUSPENDED) {
            subscription.suspend()
            if (tenantApi.findById(tenantId)?.status == TenantStatus.ACTIVE) {
                tenantApi.suspend(tenantId)
                auditor.record(
                    action = "platform.subscription.tenant.suspended",
                    entityType = "Tenant",
                    entityId = tenantId,
                    tenantId = tenantApi.platformTenantId(),
                    detail = mapOf("reason" to "langganan menunggak melewati masa tenggang"),
                )
                log.info("Tenant {} disuspend karena langganan menunggak > {} hari", tenantId, graceDays)
            }
        }

        subscriptionRepository.save(subscription)
    }
}
