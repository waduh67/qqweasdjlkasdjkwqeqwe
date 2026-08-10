package com.duluin.ftth.inbox.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inbox.application.port.outbound.OperatorNotificationRepository
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * Membuang pemberitahuan lama.
 *
 * Kotak masuk adalah daftar kerja, bukan arsip: yang berumur dua bulan sudah pasti selesai
 * ditangani atau sudah telanjur tak tertangani, dan dua-duanya tak lagi menuntut tindakan.
 * Tanpa penyapu ini tabelnya hanya tumbuh — dan yang pertama melambat justru hitungan
 * "belum dibaca" yang dipanggil tiap menit oleh tiap operator yang sedang online.
 *
 * Berjalan di luar konteks request, jadi tenant dipasang satu per satu lewat
 * [TenantContext.runAs] — sama seperti penyapu SLA helpdesk. Penanda baca ikut terhapus
 * lewat `ON DELETE CASCADE`.
 */
@Component
class InboxRetentionScheduler(
    private val tenantApi: TenantApi,
    private val worker: InboxRetentionWorker,
    @param:Value("\${ftth.inbox.retention:P60D}") private val retention: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ftth.inbox.purge-cron:0 25 3 * * *}")
    fun purge() {
        val cutoff = Instant.now() - retention
        val removed = tenantApi.findActiveTenantIds().sumOf { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { worker.purge(cutoff) } }
                .onFailure { log.warn("Pembersihan kotak masuk tenant {} gagal: {}", tenantId, it.message) }
                .getOrDefault(0)
        }
        if (removed > 0) log.info("Kotak masuk operator: {} pemberitahuan lama dibuang", removed)
    }
}

/** Pembersih satu tenant dalam transaksinya sendiri (lihat `HelpdeskSlaSweeper` untuk alasannya). */
@Component
class InboxRetentionWorker(
    private val notifications: OperatorNotificationRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun purge(cutoff: Instant): Int = notifications.deleteOlderThan(cutoff)
}
