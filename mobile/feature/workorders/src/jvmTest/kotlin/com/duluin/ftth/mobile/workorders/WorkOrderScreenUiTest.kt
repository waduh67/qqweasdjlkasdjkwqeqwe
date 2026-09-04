package com.duluin.ftth.mobile.workorders

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.duluin.ftth.mobile.domain.PermissionState
import com.duluin.ftth.mobile.domain.WorkOrderState
import com.duluin.ftth.mobile.ui.FieldOperationsTheme
import com.duluin.ftth.mobile.ui.FluentStatePanel
import com.duluin.ftth.mobile.ui.ScreenContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WorkOrderScreenUiTest {
    @Test
    fun errorStateExposesCriticalSemanticsAndRetryDispatchesLoad() = runComposeUiTest {
        var dispatched: WorkOrderIntent? = null
        setContent {
            FieldOperationsTheme {
                WorkOrderScreen(
                    state = WorkOrderUiState(workOrder = WorkOrderState.Error("Gagal memuat")),
                    onIntent = { dispatched = it },
                )
            }
        }

        onNodeWithContentDescription("Gagal memuat")
            .assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "critical"))
        onNodeWithContentDescription("Coba lagi").performClick()
        assertEquals(WorkOrderIntent.Load, dispatched)
    }

    @Test
    fun loadingOfflineAndPermissionStatesRenderTheirUserFacingCopy() = runComposeUiTest {
        setContent {
            FieldOperationsTheme {
                WorkOrderScreen(WorkOrderUiState(workOrder = WorkOrderState.Loading), onIntent = {})
            }
        }
        onNodeWithContentDescription("Memuat data lapangan...").assertExists()

        setContent {
            FieldOperationsTheme {
                WorkOrderScreen(WorkOrderUiState(workOrder = WorkOrderState.Offline), onIntent = {})
            }
        }
        onNodeWithContentDescription("Perangkat sedang offline. Perubahan akan diantrikan.").assertExists()

        setContent {
            FieldOperationsTheme {
                WorkOrderScreen(WorkOrderUiState(workOrder = WorkOrderState.PermissionRequired), onIntent = {})
            }
        }
        onNodeWithContentDescription("Izin lokasi diperlukan untuk check-in.")
            .assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "critical"))
    }

    @Test
    fun conflictRetryAndCheckInButtonsDispatchTheirIntents() = runComposeUiTest {
        var retryCalled = false
        setContent {
            FieldOperationsTheme {
                FluentStatePanel(ScreenContent.Conflict("Konflik revisi"), onRetry = { retryCalled = true }) {}
            }
        }
        onNodeWithContentDescription("Konflik revisi")
            .assertExists()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "critical"))
        onNodeWithContentDescription("Muat ulang perubahan").performClick()
        assertEquals(true, retryCalled)

        var dispatched: WorkOrderIntent? = null
        setContent {
            FieldOperationsTheme {
                WorkOrderScreen(
                    state = WorkOrderUiState(workOrder = WorkOrderState.Ready, permission = PermissionState.Granted),
                    onIntent = { dispatched = it },
                )
            }
        }
        onNodeWithContentDescription("Check-in").performClick()
        assertEquals(WorkOrderIntent.CheckIn(PermissionState.Granted), dispatched)
    }
}
