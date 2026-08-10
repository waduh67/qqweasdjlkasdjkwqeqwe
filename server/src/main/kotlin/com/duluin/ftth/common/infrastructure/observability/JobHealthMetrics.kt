package com.duluin.ftth.common.infrastructure.observability

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Menerbitkan denyut nadi pekerjaan latar sebagai metrik Micrometer, yang lalu terbaca di
 * `/actuator/prometheus`.
 *
 * Metrik yang paling berharga di sini bukan jumlah eksekusi, melainkan
 * `ftth_job_success_age_seconds`: umur sukses terakhir. Nilai yang terus menanjak adalah
 * satu-satunya tanda yang muncul ketika sebuah job berhenti bekerja — kegagalan diam yang
 * tak menghasilkan galat, tak menghasilkan log, dan tak menghasilkan keluhan sampai
 * beberapa hari kemudian.
 *
 * `ftth_job_stalled` sengaja dihitung di sisi aplikasi, bukan diserahkan ke ekspresi
 * PromQL: ambangnya bergantung pada interval tiap job (10 detik sampai 12 jam), dan aturan
 * alert yang menyalin angka itu satu per satu pasti akan ketinggalan zaman.
 */
@Component
class JobHealthMetrics(
    private val jobs: JobHealthRegistry,
    private val meters: MeterRegistry,
) {

    @EventListener(ApplicationReadyEvent::class)
    @Order(ScheduledJobDiscovery.JOB_DISCOVERY_ORDER + 10)
    fun bindJobMetrics() {
        jobs.snapshot().forEach { job ->
            gauge(job, "ftth.job.success.age", "Umur sukses terakhir pekerjaan latar", "seconds") {
                it.sinceSuccess.toMillis() / 1000.0
            }
            gauge(job, "ftth.job.stalled", "1 bila job dianggap macet (sukses terakhir kedaluwarsa)", null) {
                if (it.stalled) 1.0 else 0.0
            }
            gauge(job, "ftth.job.running", "1 bila job sedang berjalan detik ini", null) {
                if (it.running) 1.0 else 0.0
            }
            gauge(job, "ftth.job.duration.last", "Lama ronde terakhir", "seconds") {
                (it.lastDuration?.toMillis() ?: 0L) / 1000.0
            }
            gauge(job, "ftth.job.interval", "Selang terjadwal job", "seconds") {
                (it.interval?.toMillis() ?: 0L) / 1000.0
            }

            counter(job, "ftth.job.runs", "Jumlah ronde yang selesai") { it.runs.toDouble() }
            counter(job, "ftth.job.failures", "Jumlah ronde yang melempar galat") { it.failures.toDouble() }
        }
    }

    // Pengukur memegang registry (bean, hidup selama aplikasi) lalu menengok job lewat
    // namanya tiap kali diambil sampel — Micrometer menyimpan objek sumber secara lemah,
    // jadi menahan JobHealth langsung akan membuat pengukurnya lenyap tanpa jejak.
    private fun gauge(job: JobHealth, name: String, help: String, unit: String?, value: (JobHealth) -> Double) {
        Gauge.builder(name, jobs) { registry -> registry.health(job.name)?.let(value) ?: 0.0 }
            .description(help)
            .baseUnit(unit)
            .tags(tagsOf(job))
            .register(meters)
    }

    private fun counter(job: JobHealth, name: String, help: String, value: (JobHealth) -> Double) {
        FunctionCounter.builder(name, jobs) { registry -> registry.health(job.name)?.let(value) ?: 0.0 }
            .description(help)
            .tags(tagsOf(job))
            .register(meters)
    }

    /**
     * Labelnya `job_name`, BUKAN `job`: Prometheus memakai `job` untuk nama scrape-config-nya
     * sendiri, dan label yang bentrok diam-diam diganti namanya jadi `exported_job` saat
     * diserap. Aturan alert yang ditulis dengan `job=` akan cocok dengan hal yang sama sekali
     * berbeda — kesalahan yang tak menimbulkan galat apa pun, cuma peringatan yang tak pernah
     * menyala.
     */
    private fun tagsOf(job: JobHealth): Tags = Tags.of("job_name", job.name, "module", job.module)
}
