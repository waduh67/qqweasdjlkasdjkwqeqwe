package com.duluin.ftth.mobile.domain

enum class Permission { AttendanceSelf, PayslipSelf }

data class ShiftSummary(val name: String, val startsAt: String, val endsAt: String)
data class LeaveSummary(val label: String, val active: Boolean)

data class AttendanceSnapshot(
    val shift: ShiftSummary?,
    val leave: LeaveSummary?,
    val attendance: String,
    val revision: Long,
    val periodLocked: Boolean,
)

enum class AttendanceOperation { CheckIn, CheckOut }

data class AttendanceCommand(
    val operation: AttendanceOperation,
    val operationKey: String,
    val revision: Long,
)

sealed interface AttendanceSubmission {
    data class Accepted(val snapshot: AttendanceSnapshot) : AttendanceSubmission
    data class Conflict(val message: String) : AttendanceSubmission
    data object Offline : AttendanceSubmission
    data object Denied : AttendanceSubmission
}

interface AttendancePort {
    suspend fun snapshot(): Result<AttendanceSnapshot>
    suspend fun submit(command: AttendanceCommand): AttendanceSubmission
}

data class PayslipLine(val label: String, val amountMinor: Long)

data class PersonalPayslip(
    val period: String,
    val currency: String,
    val netMinor: Long,
    val lines: List<PayslipLine>,
    val periodLocked: Boolean,
)

sealed interface PayslipResult {
    data class Available(val payslip: PersonalPayslip) : PayslipResult
    data object Denied : PayslipResult
}

interface SecurePayslipPort {
    suspend fun personalPayslip(): Result<PayslipResult>
}
