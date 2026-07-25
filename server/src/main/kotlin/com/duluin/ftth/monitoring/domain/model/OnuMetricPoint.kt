package com.duluin.ftth.monitoring.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Satu titik pengukuran ONU.
 *
 * Sengaja bukan agregat: tidak punya identitas, tidak pernah diubah, dan tidak
 * menjaga invariant apa pun — hanya fakta bahwa pada waktu tertentu sebuah ONU
 * terbaca begini. Karena itu ia juga tidak dipetakan sebagai JPA entity; menulis
 * ribuan baris per siklus lewat Hibernate berarti melacak ribuan objek yang tak
 * satu pun akan diubah.
 */
data class OnuMetricPoint(
    val time: Instant,
    val tenantId: UUID,
    val onuId: UUID,
    val oltId: UUID?,
    val status: String,
    val rxPowerDbm: Double?,
    val txPowerDbm: Double?,
    val uptimeSeconds: Long?,
    val distanceMeters: Int?,
    /**
     * Penyebab putus terakhir dari register OLT (mis. DYING_GASP, LOS). Melekat
     * pada titik pengukuran, bukan pada agregat ONU, karena ia telemetri: OLT
     * menyimpannya melewati pemulihan sehingga bacaan terbaru selalu memuat sebab
     * gangguan terakhir. `null` bila OLT tidak melaporkannya.
     */
    val downCause: String?,
    /**
     * Kapan ONU terakhir putus dan terakhir kembali online menurut register OLT.
     * Telemetri seperti [downCause] — bertahan melewati pemulihan. Berpasangan
     * menjadi durasi gangguan terakhir; urutannya menandakan apakah ONU sudah
     * pulih ([lastOnAt] > [lastOffAt]) atau masih putus. `null` bila tak dilaporkan.
     */
    val lastOffAt: Instant?,
    val lastOnAt: Instant?,
)

/** Ringkasan riwayat redaman satu ONU pada rentang waktu tertentu. */
data class OpticalTrend(
    val onuId: UUID,
    val samples: Int,
    val averageRxPowerDbm: Double?,
    val minRxPowerDbm: Double?,
    val maxRxPowerDbm: Double?,
    /**
     * Perubahan redaman per hari, hasil regresi linear sederhana. Nilai negatif
     * berarti memburuk — inilah sinyal yang dicari pemeliharaan prediktif:
     * konektor kotor atau serat tertekuk memburuk pelan-pelan jauh sebelum
     * pelanggan merasakan gangguan.
     */
    val trendDbPerDay: Double?,
) {
    /** Ambang praktis: turun lebih dari 0,5 dB per hari hampir selalu kerusakan fisik. */
    val degrading: Boolean get() = (trendDbPerDay ?: 0.0) < -DEGRADATION_THRESHOLD_DB_PER_DAY

    companion object {
        const val DEGRADATION_THRESHOLD_DB_PER_DAY = 0.5
    }
}
