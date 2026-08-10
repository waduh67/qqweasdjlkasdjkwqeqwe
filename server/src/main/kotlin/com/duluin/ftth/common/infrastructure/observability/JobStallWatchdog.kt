package com.duluin.ftth.common.infrastructure.observability

import com.duluin.ftth.common.infrastructure.config.ObservabilityProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sebuah pekerjaan latar berhenti berhasil melewati ambang waktunya.
 *
 * @param repeated pengingat berkala untuk kemacetan yang belum juga dibereskan, bukan
 *        kabar pertama. Dipisah supaya penerima bisa membedakan "baru terjadi" dari
 *        "masih terjadi sejak kemarin".
 */
data class ScheduledJobStalled(val job: JobHealth, val repeated: Boolean)

/** Pekerjaan latar yang tadinya macet kembali berhasil menyelesaikan rondenya. */
data class ScheduledJobRecovered(val job: JobHealth)

/**
 * Penjaga yang memeriksa denyut nadi seluruh pekerjaan latar dan berteriak ketika salah
 * satunya berhenti.
 *
 * Berjalan di utasnya SENDIRI, bukan lewat `@Scheduled` seperti pekerjaan lain di repo ini.
 * Itu bukan gaya-gayaan: kemacetan yang paling mungkin terjadi justru kolam utas penjadwal
 * yang habis dipakai job menggantung — dan penjaga yang ikut mengantre di kolam yang sama
 * akan diam persis pada saat ia paling dibutuhkan. Penjaga yang bisa ikut macet bersama
 * yang dijaganya sama saja dengan tidak ada penjaga.
 *
 * Yang dikirim cuma peralihan keadaan (sehat→macet, macet→sehat) plus pengingat berkala,
 * karena peringatan yang datang tiap lima menit akan dianggap sampah dalam sehari.
 */
@Component
class JobStallWatchdog(
    private val jobs: JobHealthRegistry,
    private val events: ApplicationEventPublisher,
    private val properties: ObservabilityProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Kapan peringatan terakhir dikirim per job; kosong = job itu sedang sehat. */
    private val alertedAt = ConcurrentHashMap<String, Instant>()

    private val watchdogThread = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ftth-job-watchdog").apply { isDaemon = true }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val everyMillis = properties.stallCheckInterval.toMillis()
        watchdogThread.scheduleWithFixedDelay(
            { runCatching { check() }.onFailure { log.warn("Ronde penjaga job gagal: {}", it.message, it) } },
            everyMillis,
            everyMillis,
            TimeUnit.MILLISECONDS,
        )
        log.info("Penjaga pekerjaan latar aktif, memeriksa tiap {}", properties.stallCheckInterval)
    }

    @PreDestroy
    fun stop() {
        watchdogThread.shutdownNow()
    }

    /**
     * Satu ronde pemeriksaan. Terbuka untuk dipanggil langsung dari uji — menunggu jam
     * dinding hanya akan membuat pengujiannya lambat sekaligus rapuh.
     */
    fun check(now: Instant = Instant.now()) {
        jobs.snapshot(now).forEach { job ->
            if (job.stalled) alertIfDue(job, now) else recoverIfWasStalled(job)
        }
    }

    private fun alertIfDue(job: JobHealth, now: Instant) {
        val lastAlert = alertedAt[job.name]
        val due = lastAlert == null || Duration.between(lastAlert, now) >= properties.alertRepeat
        if (!due) return

        alertedAt[job.name] = now
        // Selalu masuk log, apa pun nasib emailnya: log adalah satu-satunya kanal yang tak
        // bergantung pada SMTP, jaringan, atau siapa pun yang sudah mengisi konfigurasi.
        log.warn(
            "Pekerjaan latar '{}' (modul {}) MACET — sukses terakhir {} lalu, ambang {}, galat terakhir: {}",
            job.name,
            job.module,
            job.sinceSuccess,
            job.stallAfter,
            job.lastError ?: "(tidak ada, job memang tak pernah jalan lagi)",
        )
        events.publishEvent(ScheduledJobStalled(job, repeated = lastAlert != null))
    }

    private fun recoverIfWasStalled(job: JobHealth) {
        if (alertedAt.remove(job.name) == null) return
        log.info("Pekerjaan latar '{}' kembali normal", job.name)
        events.publishEvent(ScheduledJobRecovered(job))
    }
}
