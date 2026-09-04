package com.duluin.ftth.hris.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "hris_employee")
class HrisEmployeeJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "user_id", nullable = false, updatable = false) var userId: UUID? = null
    @Column(nullable = false) var status: String = "ACTIVE"
    @Column(name = "custodian_id") var custodianId: UUID? = null
}

@Entity
@Table(name = "hris_attendance_session")
class HrisAttendanceSessionJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "employee_id", nullable = false, updatable = false) var employeeId: UUID? = null
    @Column(name = "work_date", nullable = false, updatable = false) var workDate: LocalDate = LocalDate.MIN
    @Column(name = "check_in_at", nullable = false, updatable = false) var checkInAt: Instant = Instant.EPOCH
    @Column(name = "check_out_at") var checkOutAt: Instant? = null
    @Column(nullable = false) var decision: String = "REVIEW_REQUIRED"
    @Column(name = "gps_evidence", nullable = false) var gpsEvidence: Boolean = false
}

@Entity
@Table(name = "hris_attendance_correction")
class HrisAttendanceCorrectionJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "session_id", nullable = false, updatable = false) var sessionId: UUID? = null
    @Column(name = "requester_id", nullable = false, updatable = false) var requesterId: UUID? = null
    @Column(name = "custodian_id", updatable = false) var custodianId: UUID? = null
    @Column(name = "requested_check_in") var requestedCheckIn: Instant? = null
    @Column(name = "requested_check_out") var requestedCheckOut: Instant? = null
    @Column(nullable = false, updatable = false) var reason: String = ""
    @Column(nullable = false) var state: String = "PENDING"
    @Column(name = "approver_id", updatable = false) var approverId: UUID? = null
    @Column(name = "decided_at", updatable = false) var decidedAt: Instant? = null
}
@Entity @Table(name = "hris_attendance_revision")
class HrisAttendanceRevisionJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "session_id", nullable = false, updatable = false) var sessionId: UUID? = null
    @Column(nullable = false, updatable = false) var revision: Long = 0
    @Column(name = "check_in_at", updatable = false) var checkInAt: Instant? = null
    @Column(name = "check_out_at", updatable = false) var checkOutAt: Instant? = null
    @Column(name = "correction_id", nullable = false, updatable = false) var correctionId: UUID? = null
    @Column(name = "approved_at", nullable = false, updatable = false) var approvedAt: Instant = Instant.EPOCH
}

@Entity
@Table(name = "hris_idempotency_outcome")
class HrisOutcomeJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(nullable = false, updatable = false) var namespace: String = ""
    @Column(name = "operation_key", nullable = false, updatable = false) var operationKey: String = ""
    @Column(name = "payload_hash", nullable = false, updatable = false) var payloadHash: String = ""
    @Column(name = "outcome", nullable = false, columnDefinition = "jsonb", updatable = false) var outcomeJson: String = "{}"
}

@Entity
@Table(name = "hris_outbox")
class HrisOutboxJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "aggregate_id", nullable = false, updatable = false) var aggregateId: UUID? = null
    @Column(name = "event_type", nullable = false, updatable = false) var eventType: String = ""
    @Column(nullable = false, updatable = false) var sequence: Long = 0
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false) var payload: String = "{}"
    @Column(name = "published_at") var publishedAt: Instant? = null
}
@Entity @Table(name = "hris_audit")
class HrisAuditJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "actor_id") var actorId: UUID? = null
    @Column(nullable = false, updatable = false) var action: String = ""
    @Column(name = "entity_type", nullable = false, updatable = false) var entityType: String = ""
    @Column(name = "entity_id", updatable = false) var entityId: UUID? = null
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false) var detail: String = "{}"
    @Column(name = "occurred_at", nullable = false, updatable = false) var occurredAt: Instant = Instant.EPOCH
}

@Entity @Table(name = "hris_employment")
class HrisEmploymentJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "employee_id", nullable = false, updatable = false) var employeeId: UUID? = null
    @Column(name = "valid_from", nullable = false, updatable = false) var validFrom: LocalDate = LocalDate.MIN
    @Column(name = "valid_to", updatable = false) var validTo: LocalDate? = null
    @Column(nullable = false, updatable = false) var title: String = ""
}
@Entity @Table(name = "hris_shift")
class HrisShiftJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(nullable = false, updatable = false) var name: String = ""
    @Column(name = "start_time", nullable = false, updatable = false) var startTime: java.time.LocalTime = java.time.LocalTime.MIDNIGHT
    @Column(name = "end_time", nullable = false, updatable = false) var endTime: java.time.LocalTime = java.time.LocalTime.MIDNIGHT
}
@Entity @Table(name = "hris_roster")
class HrisRosterJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "employee_id", nullable = false, updatable = false) var employeeId: UUID? = null
    @Column(name = "shift_id", nullable = false, updatable = false) var shiftId: UUID? = null
    @Column(name = "valid_from", nullable = false, updatable = false) var validFrom: LocalDate = LocalDate.MIN
    @Column(name = "valid_to", updatable = false) var validTo: LocalDate? = null
}
@Entity @Table(name = "hris_exception")
class HrisExceptionJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "employee_id", updatable = false) var employeeId: UUID? = null
    @Column(name = "exception_date", nullable = false, updatable = false) var exceptionDate: LocalDate = LocalDate.MIN
    @Column(nullable = false, updatable = false) var kind: String = "HOLIDAY"
    @Column(nullable = false, updatable = false) var reason: String = ""
}
@Entity @Table(name = "hris_attendance_period")
class HrisPeriodJpaEntity(id: UUID = UUID.randomUUID()) : TenantAwareJpaEntity(id) {
    @Column(name = "valid_from", nullable = false, updatable = false) var validFrom: LocalDate = LocalDate.MIN
    @Column(name = "valid_to", nullable = false, updatable = false) var validTo: LocalDate = LocalDate.MIN
    @Column(name = "closed_at") var closedAt: Instant? = null
    @Column(name = "reopened_at") var reopenedAt: Instant? = null
}
