package com.duluin.ftth.common.infrastructure.web

import com.duluin.ftth.common.infrastructure.observability.JobHealth
import com.duluin.ftth.common.infrastructure.observability.JobHealthRegistry
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Kesehatan pekerjaan latar untuk mata manusia, bukan untuk Prometheus.
 *
 * Metrik di `/actuator/prometheus` memang lebih lengkap, tapi ia mengandaikan ada
 * Prometheus + Grafana yang sudah berdiri. Deploy paling kecil kita belum tentu punya
 * itu, dan justru deploy kecil yang paling sering ditinggal tanpa pengawasan. Satu
 * halaman yang bisa dibuka kapan saja menutup jarak itu tanpa menambah infrastruktur.
 *
 * Lintas-tenant dan tanpa RLS sama sekali: yang dilaporkan adalah proses server, bukan
 * data ISP mana pun. Karena itu izinnya `platform.ops.view` (platform admin lolos via
 * flag) — operator tenant tak berkepentingan, dan nama job bisa membocorkan bentuk dalam
 * sistem tanpa memberi mereka manfaat apa pun.
 */
@RestController
@RequestMapping("/api/platform/jobs")
@Tag(name = "Platform — Job Health")
@SecurityRequirement(name = "bearer-jwt")
class JobHealthController(
    private val jobs: JobHealthRegistry,
) {
    @GetMapping
    @PreAuthorize("@authz.can('platform.ops.view')")
    fun list(): List<JobHealthResponse> = jobs.snapshot().map(JobHealthResponse::from)
}

/**
 * Durasi dikirim sebagai DETIK, bukan `Duration`: serialisasi `Duration` bawaan Jackson
 * berbentuk ISO-8601 (`PT2H15M`) yang harus diurai ulang di browser hanya untuk bisa
 * dibandingkan dan diformat. Angka detik langsung siap dipakai.
 */
data class JobHealthResponse(
    val name: String,
    val module: String,
    val intervalSeconds: Long?,
    val runs: Long,
    val failures: Long,
    val lastStartedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val lastError: String?,
    val lastDurationSeconds: Double?,
    val running: Boolean,
    val sinceSuccessSeconds: Long,
    val stallAfterSeconds: Long?,
    val stalled: Boolean,
) {
    companion object {
        fun from(job: JobHealth): JobHealthResponse = JobHealthResponse(
            name = job.name,
            module = job.module,
            intervalSeconds = job.interval?.seconds,
            runs = job.runs,
            failures = job.failures,
            lastStartedAt = job.lastStartedAt,
            lastSuccessAt = job.lastSuccessAt,
            lastFailureAt = job.lastFailureAt,
            lastError = job.lastError,
            // Pecahan detik dipertahankan: banyak ronde selesai di bawah satu detik, dan
            // "0" untuk semuanya menghapus justru perbedaan yang ingin dilihat.
            lastDurationSeconds = job.lastDuration?.let { it.toNanos() / 1_000_000_000.0 },
            running = job.running,
            sinceSuccessSeconds = job.sinceSuccess.seconds,
            stallAfterSeconds = job.stallAfter?.seconds,
            stalled = job.stalled,
        )
    }
}
