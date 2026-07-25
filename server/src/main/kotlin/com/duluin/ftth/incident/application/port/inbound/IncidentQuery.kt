package com.duluin.ftth.incident.application.port.inbound

import java.time.Instant
import java.util.UUID

/**
 * Membaca insiden hasil korelasi alarm.
 *
 * Insiden dipersistensi dan punya lifecycle (dibuka → diakui → selesai): satu
 * OLT modar yang berlangsung dua jam adalah satu insiden yang sama sepanjang itu,
 * dengan timeline yang bisa ditelusuri — bukan pemandangan sesaat yang hilang saat
 * di-refresh. Isinya dipelihara mesin korelasi yang menaiki pohon topologi mencari
 * akar bersama; lihat module `incident`.
 */
interface IncidentQuery {

    /** Insiden yang belum selesai (OPEN + ACKNOWLEDGED), terparah lebih dulu. */
    fun activeIncidents(): List<IncidentView>

    /** Detail satu insiden: ringkasannya, timeline, dan alarm anggotanya saat ini. */
    fun incident(id: UUID): IncidentDetail
}

/** Perubahan lifecycle insiden oleh operator. */
interface ManageIncidentUseCase {
    fun acknowledge(id: UUID): IncidentView
    fun resolve(id: UUID): IncidentView
}

data class IncidentView(
    val id: UUID,
    /** Kunci stabil akar masalah, "<TIPE>:<uuid>". */
    val key: String,
    val rootType: String,
    val rootId: UUID,
    val rootLabel: String,
    val severity: String,
    val status: String,
    val title: String,
    val alarmCount: Int,
    val affectedCustomerCount: Int,
    /**
     * Dugaan sebab blast-radius dari register ONU: POWER_OUTAGE (area mati listrik),
     * FIBER_CUT (fiber putus), MIXED (beragam), atau `null` bila datanya belum cukup.
     */
    val suspectedCause: String?,
    val openedAt: Instant,
    val lastSeenAt: Instant,
    val acknowledgedAt: Instant?,
    val resolvedAt: Instant?,
)

data class IncidentDetail(
    val incident: IncidentView,
    val timeline: List<IncidentEventView>,
    /** Alarm hidup yang saat ini menjadi anggota insiden ini (kosong bila sudah selesai). */
    val members: List<IncidentAlarm>,
)

data class IncidentEventView(
    val type: String,
    val message: String,
    val at: Instant,
)

/** Satu alarm hidup yang menjadi anggota sebuah insiden. */
data class IncidentAlarm(
    val entityType: String,
    val entityId: UUID,
    val kind: String,
    val severity: String,
    val label: String,
    /** Sebab putus terakhir dari register OLT untuk anggota ONU; `null` untuk lainnya. */
    val downCause: String? = null,
)
