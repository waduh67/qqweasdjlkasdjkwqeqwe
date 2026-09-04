package com.duluin.ftth.hris.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HrisEmployeeJpaRepository : JpaRepository<HrisEmployeeJpaEntity, UUID> {
    fun findByUserId(userId: UUID): HrisEmployeeJpaEntity?
}

interface HrisAttendanceSessionJpaRepository : JpaRepository<HrisAttendanceSessionJpaEntity, UUID> {
    fun findByEmployeeIdAndWorkDateBetween(employeeId: UUID, from: java.time.LocalDate, to: java.time.LocalDate): List<HrisAttendanceSessionJpaEntity>
}
interface HrisAttendanceCorrectionJpaRepository : JpaRepository<HrisAttendanceCorrectionJpaEntity, UUID>
interface HrisAttendanceRevisionJpaRepository : JpaRepository<HrisAttendanceRevisionJpaEntity, UUID> {
    fun findAllBySessionIdOrderByRevision(sessionId: UUID): List<HrisAttendanceRevisionJpaEntity>
}
interface HrisOutcomeJpaRepository : JpaRepository<HrisOutcomeJpaEntity, UUID> {
    fun findByTenantIdAndNamespaceAndOperationKey(tenantId: UUID, namespace: String, operationKey: String): HrisOutcomeJpaEntity?
}
interface HrisOutboxJpaRepository : JpaRepository<HrisOutboxJpaEntity, UUID> {
    fun findFirstByTenantIdAndAggregateIdOrderBySequenceDesc(tenantId: UUID, aggregateId: UUID): HrisOutboxJpaEntity?
}
interface HrisAuditJpaRepository : JpaRepository<HrisAuditJpaEntity, UUID>
interface HrisPeriodJpaRepository : JpaRepository<HrisPeriodJpaEntity, UUID> {
    fun findByTenantIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(tenantId: UUID, date: java.time.LocalDate, sameDate: java.time.LocalDate): HrisPeriodJpaEntity?
}
interface HrisEmploymentJpaRepository : JpaRepository<HrisEmploymentJpaEntity, UUID> {
    fun findByEmployeeIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(employeeId: UUID, date: java.time.LocalDate, sameDate: java.time.LocalDate): List<HrisEmploymentJpaEntity>
}
interface HrisShiftJpaRepository : JpaRepository<HrisShiftJpaEntity, UUID>
interface HrisRosterJpaRepository : JpaRepository<HrisRosterJpaEntity, UUID> {
    fun findByEmployeeIdAndValidFromLessThanEqualAndValidToGreaterThanEqual(employeeId: UUID, date: java.time.LocalDate, sameDate: java.time.LocalDate): List<HrisRosterJpaEntity>
}
interface HrisExceptionJpaRepository : JpaRepository<HrisExceptionJpaEntity, UUID> {
    fun findByExceptionDateAndEmployeeIdIsNullOrExceptionDateAndEmployeeId(date: java.time.LocalDate, sameDate: java.time.LocalDate, employeeId: UUID): List<HrisExceptionJpaEntity>
}
