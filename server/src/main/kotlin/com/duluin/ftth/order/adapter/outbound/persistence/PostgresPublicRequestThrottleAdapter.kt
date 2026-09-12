package com.duluin.ftth.order.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.TooManyRequestsException
import com.duluin.ftth.order.application.port.outbound.PublicRequestThrottle
import com.duluin.ftth.order.config.PublicOrderProperties
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Rem laju pesanan publik yang hitungannya di Postgres, bukan di memori proses.
 *
 * KENAPA berbeda dari `AttemptThrottle`: lihat catatan panjang di V183. Ringkasnya, rem di
 * memori menghitung per-instance, jadi dua replika di belakang Caddy membuat batas efektifnya
 * dua kali lipat — untuk halaman masuk itu masih aman (password tetap harus benar), untuk
 * pintu pemesanan tidak: satu permintaan yang lolos LANGSUNG menjadi satu baris sampah di
 * antrean kerja operator.
 *
 * Jendela tetap, bukan sliding window atau token bucket. Sliding window menuntut menyimpan
 * stempel waktu tiap permintaan (penyerang yang mengendalikan jumlah baris kita = persoalan
 * baru), token bucket menuntut baca-hitung-tulis yang harus dikunci sendiri. Jendela tetap
 * cukup dengan SATU pernyataan atomik dan kelemahannya bisa diterima: pada pergantian jendela
 * bisa lolos sampai dua kali batas dalam waktu berdekatan. Untuk antrean pesanan, 10 sampah
 * alih-alih 5 bukan perbedaan yang berarti.
 */
@Component
class PostgresPublicRequestThrottleAdapter(
    private val props: PublicOrderProperties,
    /**
     * Diri sendiri lewat proxy Spring. WAJIB: panggilan langsung `this.record(...)` melewati
     * proxy sehingga `@Transactional(REQUIRES_NEW)` di bawah DIAM-DIAM tidak berlaku, dan
     * pencacahan akan ikut ter-rollback bersama permintaan yang gagal — persis lubang yang
     * dijelaskan di [PublicRequestThrottle.spend].
     */
    @Lazy private val self: PostgresPublicRequestThrottleAdapter? = null,
) : PublicRequestThrottle {

    private val log = LoggerFactory.getLogger(javaClass)

    @PersistenceContext private lateinit var entityManager: EntityManager

    override fun spend(scope: String, subject: String, limit: Int, window: Duration, message: String) {
        if (!props.throttleEnabled) return
        val now = Instant.now()
        val windowStart = floorToWindow(now, window)
        /*
         * Dinaikkan DULU dan di-commit, baru diperiksa. Urutan sebaliknya (periksa lalu naikkan)
         * membuat permintaan yang selalu gagal validasi — slug ngawur, payload rusak — tak
         * pernah terhitung karena transaksinya keburu rollback. Padahal justru itulah bentuk
         * penyalahgunaan yang paling murah dan paling mungkin dilakukan berulang-ulang.
         */
        val hits = (self ?: this).record(scope, subject.take(MAX_SUBJECT_LENGTH), windowStart)
        if (hits > limit) {
            val retryAfter = Duration.between(now, windowStart.plus(window)).coerceAtLeast(Duration.ofSeconds(1))
            log.warn(
                "Rem pesanan publik menahan '{}' subjek '{}' — {} permintaan dalam jendela, sisa tunggu {} detik",
                scope, subject.take(MAX_SUBJECT_LENGTH), hits, retryAfter.seconds,
            )
            throw TooManyRequestsException("$message. Coba lagi dalam ${humanize(retryAfter)}.", retryAfter)
        }
    }

    /**
     * Satu pernyataan atomik. `ON CONFLICT DO UPDATE ... RETURNING` membuat Postgres mengunci
     * baris pencacah, jadi dua permintaan bersamaan TIDAK bisa membaca cacah yang sama lalu
     * sama-sama menulis nilai yang sama — kesalahan klasik yang membuat rem seolah menyala tapi
     * membiarkan lonjakan lewat.
     *
     * REQUIRES_NEW supaya cacah ini bertahan meski permintaan pemanggilnya kemudian gagal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(scope: String, subject: String, windowStart: Instant): Int {
        val hits = entityManager.createNativeQuery(
            """INSERT INTO public_request_throttle (scope, subject, window_start, hits, updated_at)
               VALUES (:scope, :subject, :windowStart, 1, now())
               ON CONFLICT (scope, subject, window_start)
               DO UPDATE SET hits = public_request_throttle.hits + 1, updated_at = now()
               RETURNING hits""",
        ).setParameter("scope", scope)
            .setParameter("subject", subject)
            .setParameter("windowStart", Timestamp.from(windowStart))
            .resultList.firstOrNull() as? Number
        /*
         * Nol baris seharusnya mustahil. Kalau toh terjadi, JANGAN diam-diam menganggap
         * permintaan ini bebas: rem yang gagal mencatat lebih baik dianggap tak menahan
         * apa pun daripada dianggap menahan semuanya (endpoint publik jadi mati total).
         * Kita pilih membuka pintu dan berteriak di log — kegagalan ini pasti berasal dari
         * skema, bukan dari beban.
         */
        if (hits == null) {
            log.error("Rem pesanan publik tidak mengembalikan cacah untuk lingkup '{}'", scope)
            return 0
        }
        return hits.toInt()
    }

    /**
     * Jendela yang sudah lewat tak pernah dikunjungi lagi (IP berganti terus), jadi tanpa
     * sapuan tabel ini tumbuh selamanya. Dihapus lewat `window_start` yang ber-index, bukan
     * `updated_at`, supaya sapuan tak pernah men-scan seluruh tabel.
     */
    @Scheduled(fixedDelayString = "\${ftth.order.public.throttle-sweep-delay:PT30M}")
    @Transactional
    fun sweep() {
        val cutoff = Instant.now().minus(props.throttleRetention)
        val removed = entityManager.createNativeQuery(
            "DELETE FROM public_request_throttle WHERE window_start < :cutoff",
        ).setParameter("cutoff", Timestamp.from(cutoff)).executeUpdate()
        if (removed > 0) log.debug("Menyapu {} baris rem pesanan publik yang kedaluwarsa", removed)
    }

    /**
     * Awal jendela dibulatkan ke bawah pada kelipatan [window] sejak epoch — SAMA untuk semua
     * instance. Kalau tiap instance memakai "sekarang dikurangi window", dua replika akan
     * memegang jendela yang bergeser dan kunci barisnya tak pernah bertemu: hitungannya kembali
     * terpecah, persis yang mau dihindari dengan memindahkannya ke database.
     */
    private fun floorToWindow(now: Instant, window: Duration): Instant {
        val millis = window.toMillis().coerceAtLeast(1)
        return Instant.ofEpochMilli(now.toEpochMilli() / millis * millis)
    }

    private fun humanize(duration: Duration): String = when {
        duration.toMinutes() >= 1 -> "${duration.toMinutes() + 1} menit"
        else -> "${maxOf(duration.seconds, 1)} detik"
    }

    private companion object {
        /** Sepadan dengan `public_request_throttle.subject`; kiriman lebih panjang dipotong. */
        const val MAX_SUBJECT_LENGTH = 160
    }
}
