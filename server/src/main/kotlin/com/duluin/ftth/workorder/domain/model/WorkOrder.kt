package com.duluin.ftth.workorder.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import java.time.Instant
import java.util.UUID

/** Jenis pekerjaan lapangan. */
enum class WorkOrderType { PSB, REPAIR, MIGRATION, DISMANTLE, PREVENTIVE }

enum class WorkOrderPriority { LOW, NORMAL, HIGH, URGENT }

/**
 * Alur kerja: `DRAFT → ASSIGNED → IN_PROGRESS → DONE`, dengan `CANCELLED` bisa
 * dari state mana pun sebelum selesai. Transisi ilegal ditolak di domain.
 */
enum class WorkOrderStatus {
    DRAFT,
    ASSIGNED,
    IN_PROGRESS,
    DONE,
    CANCELLED,
    ;

    val open: Boolean get() = this != DONE && this != CANCELLED
    val terminal: Boolean get() = !open
}

enum class WorkOrderEventType { CREATED, UPDATED, ASSIGNED, STARTED, COMPLETED, CANCELLED }

/** Satu entri timeline sebuah work order. */
class WorkOrderEvent private constructor(
    val id: UUID,
    val tenantId: UUID,
    val workOrderId: UUID,
    val type: WorkOrderEventType,
    val message: String,
    val actorId: UUID?,
    val at: Instant,
) {
    companion object {
        fun of(tenantId: UUID, workOrderId: UUID, type: WorkOrderEventType, message: String, actorId: UUID?, at: Instant) =
            WorkOrderEvent(UuidV7.generate(), tenantId, workOrderId, type, message, actorId, at)

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            workOrderId: UUID,
            type: WorkOrderEventType,
            message: String,
            actorId: UUID?,
            at: Instant,
        ) = WorkOrderEvent(id, tenantId, workOrderId, type, message, actorId, at)
    }
}

/**
 * Work order: satu tugas lapangan (pasang baru, perbaikan, migrasi, bongkar,
 * preventif) dengan lifecycle dan penugasan teknisi.
 *
 * Bisa berdiri sendiri (mis. preventif terjadwal) atau lahir dari sebuah insiden
 * / permintaan pelanggan — karenanya `incidentId` dan `customerId` opsional; id
 * lintas-module disimpan polos tanpa FK, dinamainya lewat kontrak module asal.
 * Tiap transisi penting dicatat ke timeline sebagai [WorkOrderEvent] tertunda,
 * lalu dipersistensi bersama agregatnya (pola sama dengan insiden).
 */
@Suppress("TooManyFunctions")
class WorkOrder private constructor(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    val type: WorkOrderType,
    title: String,
    description: String?,
    priority: WorkOrderPriority,
    customerId: UUID?,
    incidentId: UUID?,
    areaId: UUID?,
    status: WorkOrderStatus,
    assignedTo: UUID?,
    assignedAt: Instant?,
    scheduledAt: Instant?,
    startedAt: Instant?,
    completedAt: Instant?,
    resolutionNote: String?,
    cancelReason: String?,
    rxBeforeDbm: Double?,
    rxAfterDbm: Double?,
    /** Pembuat WO; `null` berarti dibuat sistem (mis. preventif dari degradasi optik), tanpa pengguna. */
    val createdBy: UUID?,
    val createdAt: Instant,
) {
    var title: String = title
        private set
    var description: String? = description
        private set
    var priority: WorkOrderPriority = priority
        private set
    var customerId: UUID? = customerId
        private set
    var incidentId: UUID? = incidentId
        private set
    var areaId: UUID? = areaId
        private set
    var status: WorkOrderStatus = status
        private set
    var assignedTo: UUID? = assignedTo
        private set
    var assignedAt: Instant? = assignedAt
        private set
    var scheduledAt: Instant? = scheduledAt
        private set
    var startedAt: Instant? = startedAt
        private set
    var completedAt: Instant? = completedAt
        private set
    var resolutionNote: String? = resolutionNote
        private set
    var cancelReason: String? = cancelReason
        private set

    /** Redaman optik (dBm) yang diukur teknisi sebelum & sesudah pengerjaan — bukti kualitas pasang. */
    var rxBeforeDbm: Double? = rxBeforeDbm
        private set
    var rxAfterDbm: Double? = rxAfterDbm
        private set

    private val pending = mutableListOf<WorkOrderEvent>()

    /** Event timeline yang belum dipersistensi; adapter menyimpannya lalu memanggil [clearPending]. */
    fun pendingEvents(): List<WorkOrderEvent> = pending.toList()

    fun clearPending() = pending.clear()

    private fun record(type: WorkOrderEventType, message: String, at: Instant, actorId: UUID?) {
        pending += WorkOrderEvent.of(tenantId, id, type, message, actorId, at)
    }

    /** Ubah rincian deskriptif selagi work order masih berjalan; status tidak tersentuh. */
    @Suppress("LongParameterList")
    fun updateDetails(
        newTitle: String,
        newDescription: String?,
        newPriority: WorkOrderPriority,
        newCustomerId: UUID?,
        newIncidentId: UUID?,
        newAreaId: UUID?,
        newScheduledAt: Instant?,
        at: Instant,
        actorId: UUID?,
    ) {
        if (status.terminal) throw ConflictException("Work order sudah $status, tak bisa diubah")
        title = validateTitle(newTitle)
        description = newDescription?.ifBlank { null }
        priority = newPriority
        customerId = newCustomerId
        incidentId = newIncidentId
        areaId = newAreaId
        scheduledAt = newScheduledAt
        record(WorkOrderEventType.UPDATED, "Rincian diperbarui", at, actorId)
    }

    /**
     * Menugaskan (atau menugaskan ulang) ke seorang teknisi. Dari DRAFT langsung
     * naik ke ASSIGNED; penugasan ulang selagi dikerjakan tidak mengubah status.
     */
    fun assign(technicianId: UUID, at: Instant, actorId: UUID?) {
        if (status.terminal) throw ConflictException("Work order sudah $status, tak bisa ditugaskan")
        assignedTo = technicianId
        assignedAt = at
        if (status == WorkOrderStatus.DRAFT) status = WorkOrderStatus.ASSIGNED
        record(WorkOrderEventType.ASSIGNED, "Ditugaskan ke teknisi", at, actorId)
    }

    /** Teknisi mulai mengerjakan. Harus sudah ditugaskan lebih dulu. */
    fun start(at: Instant, actorId: UUID?) {
        if (status == WorkOrderStatus.IN_PROGRESS) return
        if (status != WorkOrderStatus.ASSIGNED) throw ConflictException("Work order harus ditugaskan dulu sebelum dikerjakan")
        status = WorkOrderStatus.IN_PROGRESS
        startedAt = at
        record(WorkOrderEventType.STARTED, "Pengerjaan dimulai", at, actorId)
    }

    /** Menyelesaikan pekerjaan. Hanya dari IN_PROGRESS. */
    fun complete(note: String?, at: Instant, actorId: UUID?) {
        if (status != WorkOrderStatus.IN_PROGRESS) throw ConflictException("Work order harus sedang dikerjakan untuk diselesaikan")
        status = WorkOrderStatus.DONE
        completedAt = at
        resolutionNote = note?.ifBlank { null }
        record(WorkOrderEventType.COMPLETED, "Pekerjaan selesai", at, actorId)
    }

    /** Membatalkan dari state mana pun sebelum selesai. Idempotent bila sudah batal. */
    fun cancel(reason: String?, at: Instant, actorId: UUID?) {
        if (status == WorkOrderStatus.CANCELLED) return
        if (status == WorkOrderStatus.DONE) throw ConflictException("Work order sudah selesai, tak bisa dibatalkan")
        status = WorkOrderStatus.CANCELLED
        cancelReason = reason?.ifBlank { null }
        record(WorkOrderEventType.CANCELLED, reason?.let { "Dibatalkan: $it" } ?: "Dibatalkan", at, actorId)
    }

    /**
     * Merekam redaman optik (dBm) sebelum & sesudah pengerjaan sebagai bukti kualitas
     * pasang. Kedua nilai opsional (boleh diisi bertahap); di luar rentang wajar ditolak.
     * Boleh direkam sepanjang WO belum dibatalkan — termasuk saat sudah selesai.
     */
    fun recordOptical(newRxBeforeDbm: Double?, newRxAfterDbm: Double?, at: Instant, actorId: UUID?) {
        if (status == WorkOrderStatus.CANCELLED) {
            throw ConflictException("Work order sudah dibatalkan, tak bisa mencatat pengukuran")
        }
        rxBeforeDbm = validateRxPower(newRxBeforeDbm)
        rxAfterDbm = validateRxPower(newRxAfterDbm)
        record(WorkOrderEventType.UPDATED, "Pengukuran redaman optik direkam", at, actorId)
    }

    companion object {
        private const val MIN_RX_DBM = -40.0
        private const val MAX_RX_DBM = 0.0

        /** Redaman ONU GPON selalu negatif; di luar [MIN_RX_DBM]..[MAX_RX_DBM] dBm pasti salah ukur. */
        private fun validateRxPower(value: Double?): Double? {
            if (value != null && value !in MIN_RX_DBM..MAX_RX_DBM) {
                throw ConflictException("Redaman optik $value dBm di luar rentang wajar ($MIN_RX_DBM..$MAX_RX_DBM dBm)")
            }
            return value
        }

        private fun validateTitle(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) throw ConflictException("Judul work order kosong")
            return trimmed
        }

        /** Kode manusiawi diturunkan dari bagian acak id (UUIDv7), stabil sepanjang umur WO. */
        private fun deriveCode(id: UUID): String = "WO-" + id.toString().takeLast(8).uppercase()

        @Suppress("LongParameterList")
        fun open(
            tenantId: UUID,
            type: WorkOrderType,
            title: String,
            description: String?,
            priority: WorkOrderPriority,
            customerId: UUID?,
            incidentId: UUID?,
            areaId: UUID?,
            scheduledAt: Instant?,
            assignedTo: UUID?,
            createdBy: UUID?,
            at: Instant = Instant.now(),
        ): WorkOrder {
            val id = UuidV7.generate()
            val workOrder = WorkOrder(
                id = id,
                tenantId = tenantId,
                code = deriveCode(id),
                type = type,
                title = validateTitle(title),
                description = description?.ifBlank { null },
                priority = priority,
                customerId = customerId,
                incidentId = incidentId,
                areaId = areaId,
                status = WorkOrderStatus.DRAFT,
                assignedTo = null,
                assignedAt = null,
                scheduledAt = scheduledAt,
                startedAt = null,
                completedAt = null,
                resolutionNote = null,
                cancelReason = null,
                rxBeforeDbm = null,
                rxAfterDbm = null,
                createdBy = createdBy,
                createdAt = at,
            )
            workOrder.record(WorkOrderEventType.CREATED, "Work order dibuat", at, createdBy)
            // Penugasan saat pembuatan bersifat opsional — bila ada, sekalian naik ke ASSIGNED.
            if (assignedTo != null) workOrder.assign(assignedTo, at, createdBy)
            return workOrder
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            code: String,
            type: WorkOrderType,
            title: String,
            description: String?,
            priority: WorkOrderPriority,
            customerId: UUID?,
            incidentId: UUID?,
            areaId: UUID?,
            status: WorkOrderStatus,
            assignedTo: UUID?,
            assignedAt: Instant?,
            scheduledAt: Instant?,
            startedAt: Instant?,
            completedAt: Instant?,
            resolutionNote: String?,
            cancelReason: String?,
            rxBeforeDbm: Double?,
            rxAfterDbm: Double?,
            createdBy: UUID?,
            createdAt: Instant,
        ): WorkOrder = WorkOrder(
            id, tenantId, code, type, title, description, priority, customerId, incidentId, areaId,
            status, assignedTo, assignedAt, scheduledAt, startedAt, completedAt, resolutionNote,
            cancelReason, rxBeforeDbm, rxAfterDbm, createdBy, createdAt,
        )
    }
}
