package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Penyapu permintaan persetujuan gudang yang lewat tenggat.
 *
 * Sampai sekarang EXPIRED hanya ditulis kalau ada approver yang KEBETULAN membuka permintaan
 * kedaluwarsa dan mencoba memutuskannya. Permintaan yang tidak pernah disentuh siapa pun —
 * yaitu justru yang terlupakan — tinggal PENDING selamanya: antrean approver terus tumbuh
 * berisi sampah, dan mutasi stok yang digantungnya tetap PENDING_APPROVAL sehingga barangnya
 * tidak pernah benar-benar masuk MAUPUN dilepaskan. Selisih itu baru ketahuan saat stok fisik
 * diadu dengan sistem, berbulan-bulan kemudian.
 *
 * Polanya mengikuti `EvidenceRetentionWorker` (scheduler tipis + pekerja per tenant di dalam
 * `TenantContext.runAs`) dan `OrderOutboxWorker` (rujukan `@Lazy self`).
 */
@Component
class InventoryApprovalExpiryWorker(
    private val approvals: InventoryApprovalService,
    private val tenants: TenantApi? = null,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Rujukan ke diri sendiri LEWAT PROXY Spring, `@Lazy` supaya bukan dependensi melingkar
     * saat bean-nya dibuat. WAJIB dipakai untuk memanggil [sweepTenant]: panggilan langsung
     * `this.sweepTenant(...)` melewati proxy dan `@Transactional` di bawah DIAM-DIAM tidak
     * berlaku — REQUIRES_NEW-nya hilang, satu tenant yang gagal akan menyeret hasil tenant
     * lain ikut ter-rollback, dan tidak ada satu pun error yang menjelaskan kenapa.
     */
    @Lazy private val self: InventoryApprovalExpiryWorker? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.inventory.approval-expiry-delay:PT5M}")
    fun sweep() {
        val now = Instant.now(clock)
        val proxied = self ?: this
        tenants?.findActiveTenantIds()?.forEach { tenantId ->
            // Satu tenant yang bermasalah tidak boleh menghentikan penyapuan tenant lain:
            // kalau satu kebijakan approval rusak, sembilan tenant lain tetap harus bersih.
            runCatching { proxied.sweepTenant(tenantId, now) }
                .onSuccess { expired -> if (expired > 0) log.info("Persetujuan gudang kedaluwarsa tenant {}: {}", tenantId, expired) }
                .onFailure { log.warn("Gagal menyapu persetujuan gudang tenant {}: {}", tenantId, it.message) }
        }
    }

    /**
     * REQUIRES_NEW: hasil per tenant harus commit sendiri. Kalau ikut satu transaksi besar,
     * kegagalan pada tenant terakhir membatalkan penandaan EXPIRED semua tenant sebelumnya
     * dan penyapuan berikutnya mengulang seluruh pekerjaan yang sudah benar.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sweepTenant(tenantId: UUID, now: Instant = Instant.now(clock)): Int =
        TenantContext.runAs(tenantId) { approvals.expireOverdue(tenantId, now).size }
}
