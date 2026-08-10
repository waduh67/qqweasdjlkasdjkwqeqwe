package com.duluin.ftth.common.infrastructure.observability

import org.springframework.util.ClassUtils
import java.lang.reflect.Method
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Potret kesehatan SATU pekerjaan latar pada satu titik waktu. */
data class JobHealth(
    /** `NamaKelas.namaMetode`, mis. `OltPollingScheduler.pollAll`. */
    val name: String,
    /** Modul asal (segmen paket setelah `com.duluin.ftth.`), mis. `monitoring`. */
    val module: String,
    /** Interval terjadwal; null bila job dipicu cron/trigger, bukan selang tetap. */
    val interval: Duration?,
    val runs: Long,
    val failures: Long,
    val lastStartedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val lastError: String?,
    val lastDuration: Duration?,
    /** Sedang berjalan detik ini. */
    val running: Boolean,
    /** Umur sukses terakhir. Belum pernah sukses = dihitung sejak aplikasi hidup. */
    val sinceSuccess: Duration,
    /** Ambang macet yang berlaku untuk job ini; null bila intervalnya tak diketahui. */
    val stallAfter: Duration?,
    val stalled: Boolean,
)

/**
 * Denyut nadi seluruh pekerjaan latar (`@Scheduled`) dalam satu proses.
 *
 * Ini menjawab satu pertanyaan yang selama ini tak bisa dijawab siapa pun: **apakah
 * pekerjaan latar masih benar-benar jalan?** Pola di seluruh repo ini adalah penjadwal
 * luar yang menyapu tenant di dalam `runCatching { }.onFailure { log.warn(...) }` — bagus
 * karena satu tenant rusak tak menjatuhkan yang lain, tapi akibatnya kegagalan hanya
 * berupa baris log yang tak seorang pun baca. Yang jauh lebih buruk: bila SEBUAH job
 * berhenti dijadwalkan (thread penjadwal habis dipakai job lain yang menggantung), tak
 * ada baris log sama sekali — cuma kesunyian yang tampak persis seperti "tidak ada
 * masalah".
 *
 * Registry ini murni dalam memori dan sengaja begitu: pertanyaannya selalu "sejak proses
 * ini hidup, apakah job X pernah selesai?" — bukan riwayat historis. Setelah restart,
 * umur dihitung sejak boot, jadi tak ada peringatan palsu ketika aplikasi baru naik.
 *
 * Diisi otomatis oleh [ScheduledJobHealthConfig] lewat satu advisor AOP, sehingga job
 * baru ikut terpantau tanpa penulisnya perlu ingat apa pun.
 */
class JobHealthRegistry(
    private val stallFactor: Long,
    private val stallGrace: Duration,
    private val bootedAt: Instant = Instant.now(),
) {
    private val jobs = ConcurrentHashMap<String, JobState>()

    /**
     * Mendaftarkan job beserta intervalnya sebelum ia sempat berjalan sekali pun.
     *
     * Penting untuk deteksi macet: tanpa ini job berinterval panjang (mis. penagihan
     * 12 jam) baru muncul di daftar setelah ronde pertamanya — persis pada rentang waktu
     * ketika kita paling ingin tahu ia sudah terjadwal atau belum.
     *
     * @param qualifiedMethod `paket.Kelas.metode` — bentuk yang dipakai Spring saat
     *        menyebut tugas terjadwalnya. Sengaja string, bukan `Method`: pendaftaran
     *        Spring membungkus runnable job dalam kelas internalnya sendiri, sehingga
     *        objek metodenya tak lagi bisa diambil dari luar.
     */
    fun declare(qualifiedMethod: String, interval: Duration?) {
        state(qualifiedMethod.substringBeforeLast('.'), qualifiedMethod.substringAfterLast('.')).interval = interval
    }

    /**
     * Menjalankan [body] sambil mencatat denyutnya. Melempar ulang apa pun yang dilempar
     * [body] — instrumentasi tak boleh mengubah perilaku job yang diamatinya.
     */
    fun <T> track(target: Any?, method: Method, body: () -> T): T =
        state(userClass(target, method).name, method.name).track(body)

    fun health(name: String, now: Instant = Instant.now()): JobHealth? = jobs[name]?.health(now)

    fun snapshot(now: Instant = Instant.now()): List<JobHealth> =
        jobs.values.map { it.health(now) }.sortedBy { it.name }

    /**
     * Kunci job dibentuk dari NAMA kelas, bukan objeknya, supaya jalur penemuan (yang cuma
     * punya string) dan jalur eksekusi (yang punya objek) bermuara ke entri yang sama.
     */
    private fun state(className: String, methodName: String): JobState =
        jobs.computeIfAbsent("${simpleNameOf(className)}.$methodName") { name ->
            JobState(name, moduleOf(className))
        }

    private inner class JobState(val name: String, val module: String) {
        @Volatile var interval: Duration? = null

        private val runs = AtomicLong()
        private val failures = AtomicLong()
        private val running = AtomicInteger()

        @Volatile private var lastStartedAt: Instant? = null
        @Volatile private var lastSuccessAt: Instant? = null
        @Volatile private var lastFailureAt: Instant? = null
        @Volatile private var lastError: String? = null
        @Volatile private var lastDuration: Duration? = null

        fun <T> track(body: () -> T): T {
            lastStartedAt = Instant.now()
            running.incrementAndGet()
            val startedNanos = System.nanoTime()
            try {
                val result = body()
                lastDuration = Duration.ofNanos(System.nanoTime() - startedNanos)
                lastSuccessAt = Instant.now()
                lastError = null
                return result
            } catch (failure: Throwable) {
                lastDuration = Duration.ofNanos(System.nanoTime() - startedNanos)
                lastFailureAt = Instant.now()
                lastError = describe(failure)
                failures.incrementAndGet()
                throw failure
            } finally {
                running.decrementAndGet()
                runs.incrementAndGet()
            }
        }

        fun health(now: Instant): JobHealth {
            val stallAfter = interval?.let { maxOf(it.multipliedBy(stallFactor), stallGrace) }
            val sinceSuccess = Duration.between(lastSuccessAt ?: bootedAt, now)
            return JobHealth(
                name = name,
                module = module,
                interval = interval,
                runs = runs.get(),
                failures = failures.get(),
                lastStartedAt = lastStartedAt,
                lastSuccessAt = lastSuccessAt,
                lastFailureAt = lastFailureAt,
                lastError = lastError,
                lastDuration = lastDuration,
                running = running.get() > 0,
                sinceSuccess = sinceSuccess,
                stallAfter = stallAfter,
                stalled = stallAfter != null && sinceSuccess > stallAfter,
            )
        }
    }

    companion object {
        private const val BASE_PACKAGE = "com.duluin.ftth."

        /** Kelas sumber, menembus proxy CGLIB yang dipasang advisor. */
        private fun userClass(target: Any?, method: Method): Class<*> =
            if (target != null) ClassUtils.getUserClass(target) else method.declaringClass

        /** Meniru `Class.getSimpleName()` dari nama penuh, termasuk untuk kelas bersarang. */
        private fun simpleNameOf(className: String): String =
            className.substringAfterLast('.').substringAfterLast('$')

        /**
         * Modul asal job. Untuk kode kita: segmen tepat setelah paket dasar (`monitoring`,
         * `billing`, …). Untuk job milik pustaka (Spring Modulith punya beberapa): segmen
         * paket terakhir sebelum nama kelas, karena "org" bukan keterangan apa-apa.
         */
        private fun moduleOf(className: String): String =
            if (className.startsWith(BASE_PACKAGE)) {
                className.removePrefix(BASE_PACKAGE).substringBefore('.')
            } else {
                className.substringBeforeLast('.').substringAfterLast('.')
            }

        /**
         * Pesan galat dipangkas: ia berakhir sebagai label di layar & email, dan stack
         * trace utuh sudah ada di log tempat kejadiannya.
         */
        private fun describe(failure: Throwable): String {
            val message = "${failure::class.simpleName}: ${failure.message ?: "(tanpa pesan)"}"
            return if (message.length <= 300) message else message.take(297) + "..."
        }
    }
}
