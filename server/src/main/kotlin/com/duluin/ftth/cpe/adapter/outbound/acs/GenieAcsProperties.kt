package com.duluin.ftth.cpe.adapter.outbound.acs

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Koneksi ke GenieACS lewat NBI (Northbound Interface)-nya. ACS satu instance
 * melayani semua tenant, jadi kredensial ini tunggal & global — bukan per tenant.
 *
 * Nilai default menunjuk GenieACS lokal tanpa auth (mode dev). Di produksi seluruh
 * field ditimpa lewat environment (`FTTH_CPE_GENIEACS_BASE_URL`, `..._USERNAME`,
 * `..._PASSWORD`) — kredensial tidak pernah ikut ter-commit.
 */
@ConfigurationProperties(prefix = "ftth.cpe.genieacs")
data class GenieAcsProperties(
    val baseUrl: String = "http://localhost:7557",
    /** Basic auth NBI; kosong berarti tanpa auth (GenieACS dev default). */
    val username: String = "",
    val password: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(15),
    /**
     * Batas baca khusus probe kesehatan — sengaja jauh lebih pendek dari [readTimeout].
     * Probe hanya menanyakan "ACS-nya hidup?"; jawaban "tidak" yang datang setelah 15 detik
     * sama tak bergunanya dengan tak ada jawaban, dan halamannya terlanjur menggantung.
     */
    val healthTimeout: Duration = Duration.ofSeconds(3),
)
