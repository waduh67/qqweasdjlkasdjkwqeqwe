package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.AccountingRecordRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.catalog.CatalogApi
import com.duluin.ftth.catalog.PlanNetworkRef
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Penegak FUP (fair-usage) lintas tenant secara berkala — inti nilai "RADIUS jadi pusat"
 * untuk kuota: begitu pemakaian periode sebuah akun melewati kuota paketnya, ia dipindah
 * ke grup RADIUS throttle lalu CoA menurunkan sesi hidupnya; saat pemakaian turun atau
 * siklus berganti, ia dipulihkan.
 *
 * Berjalan di luar konteks request, jadi tenant dipasang satu per satu lewat
 * [TenantContext.runAs] — sama seperti scheduler penagihan & CPE. Kegagalan satu tenant
 * tidak menghentikan tenant lain.
 */
@Component
class FupScheduler(
    private val tenantApi: TenantApi,
    private val worker: FupCycleRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.bng.fup-scheduler-interval:PT1H}")
    fun enforceFup() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { worker.run() } }
                .onFailure { log.warn("Penegakan FUP tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Pekerja FUP satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [FupScheduler], bukan method privat: `@Transactional` Spring
 * berlaku lewat proxy, jadi pemanggilan dari dalam kelas yang sama tak akan pernah
 * dibungkus transaksi. REQUIRES_NEW mengurung kegagalan ke satu tenant.
 *
 * Pemakaian periode DIHITUNG DI SERVER dari hypertable `accounting_record` yang sudah
 * di-ingest tiap poll (counter kumulatif, sadar-reset), bukan dari kanal usage baru di
 * collector — data itu sudah ada dan seragam lintas semua jenis adapter BRAS.
 */
@Component
class FupCycleRunner(
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val accountingRecordRepository: AccountingRecordRepository,
    private val catalogApi: CatalogApi,
    private val bngActions: BngActionService,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun run() = enforce(currentPeriodStart(LocalDate.now()))

    /**
     * Tegakkan FUP untuk pemakaian sejak [periodStart]. Dipisah dari [run] agar bisa
     * diuji dengan awal-periode tetap (tanpa jam dinding). Hanya akun ACTIVE ber-BRAS pada
     * paket ber-FUP & berkuota yang dievaluasi; sisanya diabaikan. Pemakaian seluruh
     * kandidat ditarik sekali (satu query batch) lalu dibandingkan dengan kuota.
     */
    fun enforce(periodStart: Instant) {
        val candidates = subscriberAccessRepository.findActiveOnNas()
        if (candidates.isEmpty()) return

        val plans = HashMap<UUID, PlanNetworkRef?>()
        fun planOf(planId: UUID) = plans.getOrPut(planId) { catalogApi.findPlanNetwork(planId) }

        val onFup = candidates.filter { access ->
            val plan = planOf(access.planId)
            plan != null && plan.fupEnabled && plan.fupQuotaMb != null
        }
        if (onFup.isEmpty()) return

        val usage = accountingRecordRepository.usageSince(onFup.map { it.id }, periodStart)

        onFup.forEach { access ->
            val plan = planOf(access.planId) ?: return@forEach
            val quotaBytes = (plan.fupQuotaMb ?: return@forEach) * BYTES_PER_MB
            val used = usage[access.id] ?: 0L
            when {
                // Melewati kuota & belum throttle → remap ke grup FUP + CoA turun.
                used > quotaBytes && !access.fupThrottled -> {
                    if (bngActions.enqueueApplyFup(access, plan)) {
                        access.applyFupThrottle()
                        subscriberAccessRepository.save(access)
                    }
                }
                // Pemakaian turun/siklus berganti & masih throttle → pulihkan ke grup normal.
                used <= quotaBytes && access.fupThrottled -> {
                    bngActions.enqueueClearFup(access, plan)
                    access.clearFupThrottle()
                    subscriberAccessRepository.save(access)
                }
            }
        }
    }

    /** Awal siklus FUP = hari-1 bulan berjalan di zona sistem (selaras penerbit invoice). */
    private fun currentPeriodStart(today: LocalDate): Instant =
        today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

    private companion object {
        /** Kuota FUP dalam MB desimal (1 MB = 1e6 byte), selaras Mbps desimal di seluruh sistem. */
        const val BYTES_PER_MB = 1_000_000L
    }
}
