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

    /**
     * Titik pada jalur sejauh [distanceMeters] dari [start], diukur menyusuri geometri.
     * Di bawah 0 dijepit ke [start], di atas panjang total ke [end]. Dipakai untuk
     * memetakan hasil OTDR (jarak ke gangguan) jadi titik perkiraan di peta.
     */
    fun pointAtDistance(distanceMeters: Double): Coordinate {
        if (distanceMeters <= 0) return start
        var traversed = 0.0
        for ((a, b) in points.zipWithNext()) {
            val segment = a.distanceTo(b)
            if (segment > 0 && traversed + segment >= distanceMeters) {
                return a.interpolate(b, (distanceMeters - traversed) / segment)
            }
            traversed += segment
        }
        return end
    }

    /**
     * Titik pada jalur di pecahan [fraction] (0 = [start], 1 = [end]) dari panjang
     * geometrisnya. Berguna saat jarak harus dikonversi lebih dulu dari panjang optik
     * (yang memuat slack) ke panjang jalur yang tergambar.
     */
    fun pointAtFraction(fraction: Double): Coordinate =
        pointAtDistance(fraction.coerceIn(0.0, 1.0) * lengthMeters())

    /**
     * Salinan jalur dengan titik AWAL (ujung `from`) diganti [coord]. Titik lain —
     * termasuk tikungan di tengah — tetap. Dipakai saat simpul di ujung awal kabel
     * dipindah di peta: ujung menempel ke lokasi baru tanpa mengubah bentuk jalur.
     * Jumlah titik tak berubah, jadi jaminan minimal 2 titik tetap terjaga.
     */
    fun withStart(coord: Coordinate): RoutePath = RoutePath(listOf(coord) + points.drop(1))

    /** Kembar [withStart] untuk titik AKHIR (ujung `to`). */
    fun withEnd(coord: Coordinate): RoutePath = RoutePath(points.dropLast(1) + coord)

    /**
     * Jarak terpendek dari [coord] ke jalur ini, dalam meter — nol bila titiknya
     * tepat di atas garis.
     *
     * Dipakai untuk menjawab "apakah kabel ini benar-benar lewat depan simpul
     * itu": ODP menempel di TENGAH kabel distribusi, bukan di ujungnya, jadi
     * keanggotaan sebuah simpul pada sebuah kabel tak bisa disimpulkan dari
     * pasangan from/to-nya.
     */
    fun distanceTo(coord: Coordinate): Double =
        points.zipWithNext { a, b -> distanceToSegment(coord, a, b) }.min()

    /**
     * Jarak titik ke satu ruas. Proyeksinya dihitung di bidang lokal — derajat
     * bujur diperpendek cos(lintang) — lalu jaraknya diukur haversine seperti
     * sisa berkas ini, supaya satuannya tetap meter sejati.
     */
    private fun distanceToSegment(p: Coordinate, a: Coordinate, b: Coordinate): Double {
        val scale = Math.cos(Math.toRadians(a.latitude))
        val bx = (b.longitude - a.longitude) * scale
        val by = b.latitude - a.latitude
        val lengthSquared = bx * bx + by * by
        if (lengthSquared == 0.0) return p.distanceTo(a)
        val px = (p.longitude - a.longitude) * scale
        val py = p.latitude - a.latitude
        // Dijepit 0..1 supaya proyeksi di luar ruas jatuh ke ujung terdekatnya.
        val t = ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
        return p.distanceTo(a.interpolate(b, t))
    }

    companion object {
        const val MAX_POINTS = 2_000
        const val DEFAULT_SLACK_PERCENT = 5.0
    }
}
