package com.duluin.ftth.mobile.materials

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.ui.FieldOperationsTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class MaterialScreenUiTest {
    @Test fun serialOnlyJobExplainsInstallationWithoutOfferingMeasuredConsumption() = runComposeUiTest {
        val original = sampleWorkspace()
        val workspace = original.copy(context = original.context.copy(field = original.context.field!!.copy(hasMeasuredMaterials = false)),
            custody = original.custody.copy(items = emptyList(), totalElements = 0), issues = original.issues.copy(items = emptyList(), totalElements = 0))
        setContent { FieldOperationsTheme { MaterialScreen(MaterialUiState(MaterialEnvironment("scope", true, false, true), workspace = workspace)) {} } }
        onNodeWithContentDescription("Pemasangan perangkat dicatat melalui aset pelanggan dan diperiksa pada QA.").assertExists()
        onNodeWithContentDescription("Catat pemakaian").assertDoesNotExist()
    }
    @Test fun measuredInputRetainsDraftAndOfflineConfirmationDispatchesOnlyOneTypedAction() = runComposeUiTest {
        val workspace = sampleWorkspace(); val reducer = MaterialReducer()
        var state by mutableStateOf(MaterialUiState(MaterialEnvironment("scope", true, false, false), workspace = workspace, form = MaterialForm.Use(listOf(MaterialUseRow(workspace.custody.items.single())))))
        val actions = mutableListOf<MaterialAction>()
        setContent { FieldOperationsTheme { Column(Modifier.verticalScroll(rememberScrollState())) { MaterialScreen(state) { intent -> val next = reducer.reduce(state, intent); state = next.state; actions += next.actions } } } }
        onNodeWithContentDescription("Jumlah dipakai 1 (m)").performScrollTo().performTextInput("82,500")
        onNodeWithContentDescription("Referensi bukti pemakaian").performScrollTo().performTextInput("Bukti ukur")
        onNodeWithContentDescription("Simpan untuk dikirim").performScrollTo().performClick()
        runOnIdle {
            val action = assertIs<MaterialAction.Submit>(actions.single())
            assertEquals("82,500", assertIs<MaterialForm.Use>(action.form).rows.single().quantity)
            assertEquals(MaterialPhase.SENDING, state.phase)
            assertEquals("100000", state.workspace!!.custody.items.single().quantityBase.base)
        }
    }
    @Test fun uncertainQueueOffersSameCommandRetryWithoutDiscardOrSuccessfulStockClaim() = runComposeUiTest {
        var intent: MaterialIntent? = null
        val row = MaterialPending("same-key", "wo", "WO-01", MaterialCommandKind.ACKNOWLEDGE, SecureDeliveryState.ATTEMPTED)
        setContent { FieldOperationsTheme { MaterialScreen(MaterialUiState(MaterialEnvironment("scope", true, false, true), pending = listOf(row))) { intent = it } } }
        onNodeWithContentDescription("WO-01 · Penerimaan · Hasil belum pasti").assertExists()
        onNodeWithContentDescription("Hapus antrean WO-01").assertDoesNotExist()
        onNodeWithContentDescription("Periksa transaksi yang sama").performClick()
        assertEquals(MaterialIntent.Retry("same-key"), intent)
    }
}
