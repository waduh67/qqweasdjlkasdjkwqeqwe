package com.duluin.ftth.common.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Setelan pemantauan aplikasi terhadap DIRINYA SENDIRI.
 *
 * Bedakan dari modul `monitoring` yang memantau OLT/ONU pelanggan: yang di sini menjaga
 * hal yang jauh lebih sunyi — belasan pekerjaan latar (`@Scheduled`) yang menagih,
 * memoll, menyinkron, dan menjatuhkan alarm. Kalau salah satunya berhenti, tak ada
 * layar yang merah: tagihan cuma "tidak terbit", sesi cuma "tidak tercatat", dan
 * biasanya baru ketahuan berhari-hari kemudian lewat keluhan pelanggan.
 */
@ConfigurationProperties(prefix = "ftth.observability")
data class ObservabilityProperties(
    /**
     * Token statis untuk `/actuator/prometheus`. KOSONG = endpoint metrik tertutup rapat.
     * Sengaja bukan bearer JWT: yang menjemput metrik adalah Prometheus, bukan manusia,
     * dan token JWT kita berumur pendek sehingga mustahil dipakai scraper.
     */
    val metricsToken: String = "",
    /**
     * Alamat email penerima peringatan job macet. Kosong = peringatan hanya dicatat ke log
     * (aman untuk dev; di produksi isilah, karena log yang tak dibaca sama saja diam).
     */
    val alertEmail: String = "",
    /** Job dianggap macet bila sukses terakhirnya lebih tua dari `interval × faktor`. */
    val stallFactor: Long = 3,
    /**
     * Batas bawah ambang macet. Tanpa ini job berinterval 10 detik akan dinyatakan macet
     * hanya karena satu ronde tersendat — berisik, dan peringatan berisik cepat diabaikan.
     */
    val stallGrace: Duration = Duration.ofMinutes(10),
    /** Selang watchdog memeriksa seluruh job. */
    val stallCheckInterval: Duration = Duration.ofMinutes(5),
) {
    init {
        require(stallFactor >= 1) { "ftth.observability.stall-factor minimal 1" }
        require(!stallGrace.isNegative && !stallGrace.isZero) { "ftth.observability.stall-grace harus > 0" }
        require(!stallCheckInterval.isNegative && !stallCheckInterval.isZero) {
            "ftth.observability.stall-check-interval harus > 0"
        }
    }
}
