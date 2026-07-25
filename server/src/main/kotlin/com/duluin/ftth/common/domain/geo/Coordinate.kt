package com.duluin.ftth.common.domain.geo

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Titik koordinat WGS-84 (SRID 4326).
 *
 * Urutan argumen sengaja `longitude` dulu agar konsisten dengan GeoJSON/PostGIS
 * (`[lon, lat]`) — sumber bug klasik saat dicampur dengan konvensi "lat, lon".
 */
data class Coordinate(val longitude: Double, val latitude: Double) {

    init {
        if (longitude !in -180.0..180.0) throw ValidationException("Longitude harus antara -180 dan 180")
        if (latitude !in -90.0..90.0) throw ValidationException("Latitude harus antara -90 dan 90")
    }

    /**
     * Titik pada garis lurus menuju [other], pada pecahan [fraction] (0 = titik ini,
     * 1 = [other]). Interpolasi linear lon/lat sudah cukup akurat di rentang satu ruas
     * kabel (puluhan sampai ratusan meter) — sama pendekatannya dengan haversine di sini.
     */
    fun interpolate(other: Coordinate, fraction: Double): Coordinate {
        val t = fraction.coerceIn(0.0, 1.0)
        return Coordinate(
            longitude = longitude + (other.longitude - longitude) * t,
            latitude = latitude + (other.latitude - latitude) * t,
        )
    }

    /** Jarak haversine dalam meter — cukup untuk estimasi panjang kabel & radius. */
    fun distanceTo(other: Coordinate): Double {
        val phi1 = Math.toRadians(latitude)
        val phi2 = Math.toRadians(other.latitude)
        val deltaPhi = Math.toRadians(other.latitude - latitude)
        val deltaLambda = Math.toRadians(other.longitude - longitude)
        val a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
            Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    companion object {
        const val SRID = 4326
        private const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}
