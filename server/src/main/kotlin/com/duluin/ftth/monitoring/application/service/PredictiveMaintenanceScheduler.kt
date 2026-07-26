package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.integration.OpticalDegradationDetected
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.application.port.outbound.OnuMetricRepository
import com.duluin.ftth.monitoring.domain.model.OpticalTrend
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Pemeliharaan prediktif: memindai deret redaman tiap tenant dan mengangkat sinyal
 * untuk ONU yang memburuk pelan-pelan.
 *
 * Inilah nilai dari menyimpan riwayat metrik — bukan sekadar melihat kondisi
 * sekarang, tapi melihat arah. Konektor kotor atau serat tertekuk menurunkan
 * redaman berhari-hari sebelum ONU benar-benar mati; menangkapnya lebih awal
 * mengubah gangguan mendadak menjadi kunjungan preventif terjadwal.
 *
 * Berjalan lintas tenant, jadi tenant context dipasang per tenant. Sumber daftar
 * tenant-nya adalah collector aktif (satu-satunya tenant yang punya metrik),
 * sama seperti [SilentCollectorWatchdog]. Kegagalan satu tenant tidak menghentikan
 * pemindaian tenant lain.
 */
@Component
class PredictiveMaintenanceScheduler(
    private val collectorRepository: CollectorRepository,
    private val scanner: PredictiveMaintenanceScanner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.monitoring.predictive-interval:PT6H}")
    fun scanAll() {
        collectorRepository.findAllActive()
            .map { it.tenantId }
            .distinct()
            .forEach { tenantId ->
                runCatching {
                    TenantContext.runAs(tenantId) { scanner.scan(tenantId) }
                }.onFailure {
                    log.warn("Pemindaian prediktif tenant {} gagal: {}", tenantId, it.message)
                }
            }
    }
}

/**
 * Pemindaian satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [PredictiveMaintenanceScheduler], bukan method privat:
 * `@Transactional` Spring berlaku lewat proxy, jadi pemanggilan dari dalam kelas
 * yang sama tak akan pernah dibungkus transaksi. REQUIRES_NEW membatasi kegagalan
 * ke satu tenant. Read-only karena pemindaian hanya membaca — pembuatan work order
 * dilakukan module workorder di transaksinya sendiri saat menerima event.
 *
 * Event diterbitkan per ONU memburuk; consumer (workorder) yang meredam duplikat,
 * sehingga pemindaian berulang tidak menumpuk work order untuk ONU yang sama.
 */
@Component
class PredictiveMaintenanceScanner(
    private val metricRepository: OnuMetricRepository,
    private val events: ApplicationEventPublisher,
    @Value("\${ftth.monitoring.predictive-window-days:14}") private val windowDays: Long,
    @Value("\${ftth.monitoring.predictive-min-samples:6}") private val minSamples: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun scan(tenantId: UUID) {
        val since = Instant.now().minus(Duration.ofDays(windowDays))
        val degrading = metricRepository.findDegrading(
            since = since,
            minSamples = minSamples,
            thresholdDbPerDay = OpticalTrend.DEGRADATION_THRESHOLD_DB_PER_DAY,
        )
        if (degrading.isEmpty()) return
        log.info("{} ONU memburuk terdeteksi untuk tenant {}", degrading.size, tenantId)
        degrading.forEach { trend ->
            events.publishEvent(
                OpticalDegradationDetected(
                    tenantId = tenantId,
                    onuId = trend.onuId,
                    // Terjamin non-null: findDegrading menyaring justru pada kemiringan.
                    trendDbPerDay = trend.trendDbPerDay ?: 0.0,
                    averageRxPowerDbm = trend.averageRxPowerDbm,
                    minRxPowerDbm = trend.minRxPowerDbm,
                    samples = trend.samples,
                ),
            )
        }
    }
}
