package com.duluin.ftth.mobile.attendance

import com.duluin.ftth.mobile.domain.AttendanceOperation
import com.duluin.ftth.mobile.domain.AttendanceSnapshot
import com.duluin.ftth.mobile.domain.LeaveSummary
import com.duluin.ftth.mobile.domain.Permission
import com.duluin.ftth.mobile.domain.ShiftSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class AttendanceReducerTest {
    private val reducer = AttendanceReducer()

    @Test
    fun checkInQueuesAnIdempotentOperationWhenEmployeeCanManageOwnAttendance() {
        val transition = reducer.reduce(
            AttendanceUiState(
                snapshot = AttendanceSnapshot(null, null, "Belum check-in", revision = 1, periodLocked = false),
                permissions = setOf(Permission.AttendanceSelf),
            ),
            AttendanceIntent.CheckIn,
        )

        assertEquals(AttendanceStatus.Submitting, transition.state.status)
        assertEquals(1, transition.actions.size)
        assertEquals(AttendanceAction.Submit(AttendanceOperation.CheckIn), transition.actions.single())
    }

    @Test
    fun conflictingSubmissionRequiresExplicitReloadInsteadOfOverwritingState() {
        val snapshot = AttendanceSnapshot(
            shift = ShiftSummary("Pagi", "08:00", "17:00"),
            leave = LeaveSummary("Tidak ada cuti", false),
            attendance = "Belum check-in",
            revision = 3,
            periodLocked = false,
        )

        val transition = reducer.reduce(
            AttendanceUiState(snapshot = snapshot, status = AttendanceStatus.Submitting),
            AttendanceIntent.SubmissionConflicted("Revisi kehadiran telah berubah"),
        )

        assertEquals(AttendanceStatus.Conflict("Revisi kehadiran telah berubah"), transition.state.status)
        assertEquals(emptyList(), transition.actions)
    }

    @Test
    fun employeeCannotSeeOrTriggerHrReviewActions() {
        val transition = reducer.reduce(AttendanceUiState(), AttendanceIntent.CheckIn)

        assertEquals(AttendanceStatus.PermissionDenied, transition.state.status)
        assertEquals(emptyList(), transition.actions)
    }
}
