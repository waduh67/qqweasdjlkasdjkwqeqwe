package com.duluin.ftth.hris.application.port

import com.duluin.ftth.hris.domain.AttendanceCorrection
import com.duluin.ftth.hris.domain.AttendanceSession
import java.time.LocalDate
import java.util.UUID
import com.duluin.ftth.common.domain.error.ConflictException

interface HrisAttendanceRepository {
    fun save(session: AttendanceSession): AttendanceSession
    fun find(id: UUID): AttendanceSession?
    fun approved(employeeId: UUID, from: LocalDate, to: LocalDate): List<AttendanceSession>
}
interface HrisCorrectionRepository {
    fun save(correction: AttendanceCorrection): AttendanceCorrection
    fun find(id: UUID): AttendanceCorrection?
}
data class HrisOutcomePayload(val targetId: UUID, val actorId: UUID?, val resultId: UUID, val resultType: String, val state: String, val fromDate: LocalDate? = null, val toDate: LocalDate? = null)
data class HrisStoredOutcome(val payloadHash: String, val payload: HrisOutcomePayload)
interface HrisOutcomeStore {
    fun find(tenantId: UUID, namespace: String, operationKey: String): HrisStoredOutcome?
    fun record(tenantId: UUID, namespace: String, operationKey: String, payloadHash: String, payload: HrisOutcomePayload): HrisStoredOutcome
}
interface HrisEventStore {
    fun append(tenantId: UUID, aggregateId: UUID, type: String, sequence: Long, occurredAt: java.time.Instant, detail: Map<String, Any?> = emptyMap())
}
data class HrisPeriodRef(val id: UUID, val from: LocalDate, val to: LocalDate, val status: String)
interface HrisPeriodStore { fun requireOpen(tenantId: UUID, date: java.time.LocalDate); fun findCovering(tenantId: UUID, date: LocalDate): HrisPeriodRef? }
class InMemoryHrisPeriodStore : HrisPeriodStore {
    private val periods = mutableListOf<HrisPeriodRef>()
    fun add(period: HrisPeriodRef) { periods += period }
    override fun requireOpen(tenantId: UUID, date: java.time.LocalDate) { if (findCovering(tenantId, date)?.status == "CLOSED") throw ConflictException("attendance period is closed") }
    override fun findCovering(tenantId: UUID, date: LocalDate): HrisPeriodRef? = periods.firstOrNull { it.from <= date && it.to >= date }
}
data class HrisPolicySnapshot(
    val employee: com.duluin.ftth.hris.domain.EmployeeProfile,
    val shift: com.duluin.ftth.hris.domain.Shift?,
    val roster: com.duluin.ftth.hris.domain.RosterAssignment?,
    val exceptions: List<com.duluin.ftth.hris.domain.LeaveHolidayException>,
    val workDate: java.time.LocalDate,
)
interface HrisPolicyRepository { fun resolve(employeeId: UUID, workDate: java.time.LocalDate): HrisPolicySnapshot }
class InMemoryHrisEventStore : HrisEventStore {
    val events = mutableListOf<com.duluin.ftth.hris.HrisAttendanceEvent>()
    override fun append(tenantId: UUID, aggregateId: UUID, type: String, sequence: Long, occurredAt: java.time.Instant, detail: Map<String, Any?>) {
        val next = (events.filter { it.tenantId == tenantId && it.aggregateId == aggregateId }.maxOfOrNull { it.sequence } ?: 0) + 1
        events += com.duluin.ftth.hris.HrisAttendanceEvent(tenantId, aggregateId, next, type, occurredAt)
    }
}
class InMemoryHrisOutcomeStore : HrisOutcomeStore {
    private val values = java.util.concurrent.ConcurrentHashMap<Triple<UUID, String, String>, HrisStoredOutcome>()
    override fun find(tenantId: UUID, namespace: String, operationKey: String) = values[Triple(tenantId, namespace, operationKey)]
    override fun record(tenantId: UUID, namespace: String, operationKey: String, payloadHash: String, payload: HrisOutcomePayload): HrisStoredOutcome {
        val key = Triple(tenantId, namespace, operationKey)
        val candidate = HrisStoredOutcome(payloadHash, payload)
        val existing = values.putIfAbsent(key, candidate) ?: return candidate
        if (existing.payloadHash != payloadHash) throw ConflictException("Operation key was used with another payload")
        return existing
    }
}
class InMemoryHrisAttendanceRepository : HrisAttendanceRepository {
    private val values = linkedMapOf<UUID, AttendanceSession>()
    override fun save(session: AttendanceSession) = session.also { values[it.id] = it }
    override fun find(id: UUID) = values[id]
    override fun approved(employeeId: UUID, from: LocalDate, to: LocalDate) = values.values.filter { it.employeeId == employeeId && it.workDate in from..to && it.decision != com.duluin.ftth.hris.AttendanceDecision.REJECTED }
}
class InMemoryHrisCorrectionRepository : HrisCorrectionRepository {
    private val values = linkedMapOf<UUID, AttendanceCorrection>()
    override fun save(correction: AttendanceCorrection) = correction.also { values[it.id] = it }
    override fun find(id: UUID) = values[id]
}
