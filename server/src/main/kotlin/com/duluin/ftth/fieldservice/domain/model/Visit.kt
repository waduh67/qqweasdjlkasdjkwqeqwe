package com.duluin.ftth.fieldservice.domain.model

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.fieldservice.VisitCancellationCause
import java.time.Instant
import java.util.UUID

enum class VisitState { PLANNED, CHECKED_IN, ON_SITE, CHECKED_OUT, SUBMITTED, CONFLICT, CANCELLED }
enum class AttendanceDecision { ACCEPTED, REVIEW_REQUIRED, REJECTED }

data class CommandMetadata(
    val tenantId: UUID,
    val actorId: UUID,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val revision: Long,
    val supervisor: Boolean = false,
)

data class Attendance(
    val decision: AttendanceDecision,
    val reason: String?,
    val serverReceivedAt: Instant,
)

/**
 * Hasil kunjungan yang GAGAL. Sebelum V191 `Visit.cancel` menerima alasan lalu membuangnya:
 * adapter tidak pernah menyimpannya dan tak ada endpoint yang memanggilnya, sehingga kunjungan
 * yang gagal karena rumah terkunci tampak persis sama dengan yang dibatalkan dispatcher.
 *
 * [reason] catatan internal teknisi; [cause] yang dibaca mesin. Lihat [VisitCancellationCause].
 */
data class VisitCancellation(
    val cause: VisitCancellationCause,
    val reason: String,
    val cancelledAt: Instant,
)

data class VisitEvent(
    val tenantId: UUID,
    val visitId: UUID,
    val type: String,
    val revision: Long,
    val actorId: UUID,
    val occurredAt: Instant,
)

class Visit private constructor(
    val id: UUID,
    val tenantId: UUID,
    val orderId: UUID,
    val workOrderId: UUID,
    val technicianId: UUID,
    var state: VisitState,
    var revision: Long,
    var assignmentActive: Boolean,
    var attendance: Attendance?,
    private val eventLog: MutableList<VisitEvent>,
    var cancellation: VisitCancellation? = null,
) {
    val events: List<VisitEvent> get() = eventLog.toList()

    fun checkIn(command: CommandMetadata, receivedAt: Instant, decision: AttendanceDecision, reason: String?) {
        transition(command, VisitState.PLANNED, VisitState.CHECKED_IN, receivedAt)
        attendance = Attendance(decision, reason, receivedAt)
    }

    fun onSite(command: CommandMetadata, receivedAt: Instant) = transition(command, VisitState.CHECKED_IN, VisitState.ON_SITE, receivedAt)

    fun checkOut(command: CommandMetadata, receivedAt: Instant) = transition(command, VisitState.ON_SITE, VisitState.CHECKED_OUT, receivedAt)

    fun submit(command: CommandMetadata, receivedAt: Instant) = transition(command, VisitState.CHECKED_OUT, VisitState.SUBMITTED, receivedAt)

    fun revokeAssignment() {
        assignmentActive = false
    }

    /**
     * [cause] SENGAJA nullable dengan default null: jalur pembatalan yang sudah ada (dispatcher
     * membatalkan penugasan yang salah) tidak punya sebab lapangan untuk dilaporkan, dan
     * memaksanya mengarang sebab justru mencemari laporan "berapa kunjungan gagal karena
     * pelanggan". Endpoint lapangan yang baru MEWAJIBKANNYA.
     */
    fun cancel(
        command: CommandMetadata,
        reason: String,
        cause: VisitCancellationCause? = null,
        at: Instant = Instant.now(),
    ) {
        if (reason.isBlank()) throw ConflictException("Cancellation reason is required")
        if (!command.supervisor && (command.actorId != technicianId || !assignmentActive)) {
            throw AccessDeniedException("Only assigned technician may cancel an active visit")
        }
        if (state !in setOf(VisitState.PLANNED, VisitState.CHECKED_IN, VisitState.ON_SITE, VisitState.CONFLICT)) {
            throw ConflictException("Visit is terminal")
        }
        if (state == VisitState.CHECKED_IN && !command.supervisor) {
            throw AccessDeniedException("Checked-in cancellation requires supervisor")
        }
        state = VisitState.CANCELLED
        cancellation = cause?.let { VisitCancellation(it, reason.trim().take(MAX_CANCELLATION_REASON), at) }
        revision += 1
    }

    fun rebaseAndSubmit(command: CommandMetadata) {
        if (state != VisitState.CONFLICT) throw ConflictException("Visit is not conflicted")
        if (command.actorId != technicianId) throw AccessDeniedException("Only assigned technician may rebase")
        if (!assignmentActive) throw ConflictException("Assignment is revoked")
        state = VisitState.SUBMITTED
        revision += 1
    }

    private fun transition(command: CommandMetadata, expected: VisitState, next: VisitState, at: Instant) {
        if (state != expected) throw ConflictException("Visit cannot transition from $state to $next")
        if (command.tenantId != tenantId || command.actorId != technicianId || !assignmentActive) {
            state = VisitState.CONFLICT
            throw ConflictException("Assignment is stale or revoked")
        }
        if (command.revision != revision) {
            state = VisitState.CONFLICT
            throw ConflictException("Visit revision ${revision} is newer than command ${command.revision}")
        }
        state = next
        revision += 1
        eventLog += VisitEvent(tenantId, id, next.eventType(), revision, command.actorId, at)
    }

    private fun VisitState.eventType() = when (this) {
        VisitState.CHECKED_IN -> "VisitCheckedIn"
        VisitState.ON_SITE -> "VisitOnSite"
        VisitState.CHECKED_OUT -> "VisitCheckedOut"
        VisitState.SUBMITTED -> "VisitSubmitted"
        else -> error("No public event for $this")
    }

    companion object {
        /** Sepadan dengan `fieldservice_visit.cancellation_reason varchar(500)` (V191). */
        private const val MAX_CANCELLATION_REASON = 500

        fun plan(tenantId: UUID, orderId: UUID, workOrderId: UUID, technicianId: UUID, plannedAt: Instant): Visit =
            Visit(UUID.randomUUID(), tenantId, orderId, workOrderId, technicianId, VisitState.PLANNED, 0, true, null, mutableListOf())

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            orderId: UUID,
            workOrderId: UUID,
            technicianId: UUID,
            state: VisitState,
            revision: Long,
            assignmentActive: Boolean,
            attendance: Attendance?,
            cancellation: VisitCancellation? = null,
        ): Visit = Visit(id, tenantId, orderId, workOrderId, technicianId, state, revision, assignmentActive, attendance,
            mutableListOf(), cancellation)
    }
}
