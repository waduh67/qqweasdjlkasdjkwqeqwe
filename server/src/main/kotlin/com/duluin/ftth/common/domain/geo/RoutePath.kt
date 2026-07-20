package com.duluin.ftth.common.domain.geo

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Jalur (polyline) sebuah kabel: urutan titik dari ujung awal ke ujung akhir.
 *
 * Panjang dihitung dari geometri, bukan diinput manual, supaya angka di UI selalu
 * konsisten dengan jalur yang digambar di peta.
 */
data class RoutePath(val points: List<Coordinate>) {

    init {
        if (points.size < 2) throw ValidationException("Jalur kabel minimal 2 titik")
        if (points.size > MAX_POINTS) throw ValidationException("Jalur kabel maksimal $MAX_POINTS titik")
    }

    val start: Coordinate get() = points.first()

    val end: Coordinate get() = points.last()

    /** Panjang jalur dalam meter. Belum memperhitungkan slack/sag — lihat [withSlack]. */
    fun lengthMeters(): Double = points.zipWithNext { a, b -> a.distanceTo(b) }.sum()

    /**
     * Panjang kabel terpakai termasuk cadangan (slack) di tiang/closure. Praktik
     * lapangan biasa memakai 5-10%; nilai baku 5%.
     */
    fun withSlack(percent: Double = DEFAULT_SLACK_PERCENT): Double = lengthMeters() * (1 + percent / 100)

    companion object {
        const val MAX_POINTS = 2_000
        const val DEFAULT_SLACK_PERCENT = 5.0
    }
}
