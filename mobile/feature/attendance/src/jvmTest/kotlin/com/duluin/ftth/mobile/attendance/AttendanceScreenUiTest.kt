package com.duluin.ftth.mobile.attendance

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import com.duluin.ftth.mobile.domain.AttendanceSnapshot
import com.duluin.ftth.mobile.domain.Permission
import com.duluin.ftth.mobile.ui.FieldOperationsTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AttendanceScreenUiTest {
    @Test
    fun conflictIsSemanticallyVisibleAndReloadsInsteadOfOverwriting() = runComposeUiTest {
        var dispatched: AttendanceIntent? = null
        setContent { FieldOperationsTheme { AttendanceScreen(AttendanceUiState(status = AttendanceStatus.Conflict("Revisi berubah")), { dispatched = it }) } }

        onNodeWithContentDescription("Revisi berubah").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Revisi berubah")))
        onNodeWithContentDescription("Muat ulang perubahan").performClick()
        assertEquals(AttendanceIntent.Load, dispatched)
    }

    @Test
    fun unlockedSelfServiceAttendanceExposesCheckInOnlyToEmployee() = runComposeUiTest {
        var dispatched: AttendanceIntent? = null
        setContent {
            FieldOperationsTheme {
                AttendanceScreen(
                    AttendanceUiState(
                        snapshot = AttendanceSnapshot(null, null, "Belum check-in", 1, periodLocked = false),
                        status = AttendanceStatus.Ready,
                        permissions = setOf(Permission.AttendanceSelf),
                    ),
                    { dispatched = it },
                )
            }
        }

        onNodeWithContentDescription("Check-in").performClick()
        assertEquals(AttendanceIntent.CheckIn, dispatched)
    }
}
