package com.duluin.ftth.incident.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import java.time.Instant
import java.util.UUID

/** Tipe entitas yang bisa menjadi akar masalah sebuah insiden. */
enum class IncidentRootType { OLT, ODC, ODP, ONU, COLLECTOR }

enum class IncidentSeverity { INFO, WARNING, CRITICAL }

enum class IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED,
    ;

    val open: Boolean get() = this != RESOLVED
}

enum class IncidentEventType { OPENED, SEVERITY_CHANGED, ACKNOWLEDGED, RESOLVED }

/** Satu entri timeline sebuah insiden. */
class IncidentEvent private constructor(
    val id: UUID,
    val tenantId: UUID,
    val incidentId: UUID,
    val type: IncidentEventType,
    val message: String,
    val actorId: UUID?,
    val at: Instant,
) {
    companion object {
        fun of(tenantId: UUID, incidentId: UUID, type: IncidentEventType, message: String, actorId: UUID?, at: Instant) =
            IncidentEvent(UuidV7.generate(), tenantId, incidentId, type, message, actorId, at)

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            incidentId: UUID,
            type: IncidentEventType,
            message: String,
            actorId: UUID?,
            at: Instant,
        ) = IncidentEvent(id, tenantId, incidentId, type, message, actorId, at)
    }
}

/**
 * Insiden: sekumpulan alarm yang berbagi satu akar masalah, dengan lifecycle-nya
 * sendiri.
 *
 * Berumur panjang seperti alarm: siklus korelasi berikutnya yang menemukan akar
 * yang sama MEMPERBARUI insiden ini ([refresh]) alih-alih membuat yang baru. Saat
 * akarnya tidak lagi beralarm, insiden ditutup otomatis ([resolve] `auto`). Tiap
 * transisi penting dicatat ke timeline sebagai [IncidentEvent] yang tertunda,
 * lalu dipersistensi bersama agregatnya.
 */
class Incident private constructor(
    val id: UUID,
    val tenantId: UUID,
    val rootType: IncidentRootType,
    val rootId: UUID,
    rootLabel: String,
    severity: IncidentSeverity,
    status: IncidentStatus,
    title: String,
    alarmCount: Int,
    affectedCustomerCount: Int,
    val openedAt: Instant,
    lastSeenAt: Instant,
    acknowledgedAt: Instant?,
    acknowledgedBy: UUID?,
    resolvedAt: Instant?,
) {
    var rootLabel: String = rootLabel
        private set
    var severity: IncidentSeverity = severity
        private set
    var status: IncidentStatus = status
        private set
    var title: String = title
        private set
    var alarmCount: Int = alarmCount
        private set
    var affectedCustomerCount: Int = affectedCustomerCount
        private set
    var lastSeenAt: Instant = lastSeenAt
        private set
    var acknowledgedAt: Instant? = acknowledgedAt
        private set
    var acknowledgedBy: UUID? = acknowledgedBy
        private set
    var resolvedAt: Instant? = resolvedAt
        private set

    private val pending = mutableListOf<IncidentEvent>()

    /** Event timeline yang belum dipersistensi; adapter menyimpannya bersama agregat lalu memanggil [clearPending]. */
    fun pendingEvents(): List<IncidentEvent> = pending.toList()

    fun clearPending() = pending.clear()

    private fun record(type: IncidentEventType, message: String, at: Instant, actorId: UUID? = null) {
        pending += IncidentEvent.of(tenantId, id, type, message, actorId, at)
    }

    /**
     * Memperbarui dari hasil korelasi terbaru. Keparahan yang berubah dicatat di
     * timeline; jumlah alarm/pelanggan diperbarui diam-diam (terlalu sering untuk
     * jadi entri timeline). Insiden yang sudah selesai tidak diperbarui.
     */
    fun refresh(
        newSeverity: IncidentSeverity,
        newTitle: String,
        newRootLabel: String,
        newAlarmCount: Int,
        newAffectedCustomerCount: Int,
        at: Instant,
    ) {
        if (status == IncidentStatus.RESOLVED) return
        if (newSeverity != severity) {
            record(IncidentEventType.SEVERITY_CHANGED, "Keparahan $severity → $newSeverity", at)
            severity = newSeverity
        }
        rootLabel = newRootLabel
        title = newTitle
        alarmCount = newAlarmCount
        affectedCustomerCount = newAffectedCustomerCount
        lastSeenAt = at
    }

    /** Operator mengakui insiden: tetap terbuka, tapi berhenti menuntut perhatian. */
    fun acknowledge(actorId: UUID, at: Instant = Instant.now()) {
        if (status == IncidentStatus.RESOLVED) throw ConflictException("Insiden sudah selesai")
        if (status == IncidentStatus.ACKNOWLEDGED) return
        status = IncidentStatus.ACKNOWLEDGED
        acknowledgedAt = at
        acknowledgedBy = actorId
        record(IncidentEventType.ACKNOWLEDGED, "Diakui operator", at, actorId)
    }

    /** Akar masalah sudah pulih (`auto`) atau operator menutupnya. Idempotent. */
    fun resolve(at: Instant = Instant.now(), auto: Boolean = false, actorId: UUID? = null) {
        if (status == IncidentStatus.RESOLVED) return
        status = IncidentStatus.RESOLVED
        resolvedAt = at
        record(
            IncidentEventType.RESOLVED,
            if (auto) "Pulih otomatis — akar masalah tidak lagi beralarm" else "Ditutup manual",
            at,
            actorId,
        )
    }

    companion object {
        @Suppress("LongParameterList")
        fun open(
            tenantId: UUID,
            rootType: IncidentRootType,
            rootId: UUID,
            rootLabel: String,
            severity: IncidentSeverity,
            title: String,
            alarmCount: Int,
            affectedCustomerCount: Int,
            at: Instant = Instant.now(),
        ): Incident {
            val incident = Incident(
                id = UuidV7.generate(),
                tenantId = tenantId,
                rootType = rootType,
                rootId = rootId,
                rootLabel = rootLabel,
                severity = severity,
                status = IncidentStatus.OPEN,
                title = title,
                alarmCount = alarmCount,
                affectedCustomerCount = affectedCustomerCount,
                openedAt = at,
                lastSeenAt = at,
                acknowledgedAt = null,
                acknowledgedBy = null,
                resolvedAt = null,
            )
            incident.record(IncidentEventType.OPENED, "Insiden terbuka: $title", at)
            return incident
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            rootType: IncidentRootType,
            rootId: UUID,
            rootLabel: String,
            severity: IncidentSeverity,
            status: IncidentStatus,
            title: String,
            alarmCount: Int,
            affectedCustomerCount: Int,
            openedAt: Instant,
            lastSeenAt: Instant,
            acknowledgedAt: Instant?,
            acknowledgedBy: UUID?,
            resolvedAt: Instant?,
        ): Incident = Incident(
            id, tenantId, rootType, rootId, rootLabel, severity, status, title, alarmCount,
            affectedCustomerCount, openedAt, lastSeenAt, acknowledgedAt, acknowledgedBy, resolvedAt,
        )
    }
}
