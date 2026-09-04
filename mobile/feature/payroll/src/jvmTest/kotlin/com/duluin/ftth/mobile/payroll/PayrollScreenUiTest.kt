package com.duluin.ftth.mobile.payroll

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import com.duluin.ftth.mobile.domain.PersonalPayslip
import com.duluin.ftth.mobile.ui.FieldOperationsTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PayrollScreenUiTest {
    @Test
    fun lockedPayslipRendersReadOnlyStateWithoutPayrollActions() = runComposeUiTest {
        setContent {
            FieldOperationsTheme {
                PayrollScreen(
                    PayrollUiState(
                        payslip = PersonalPayslip("Agustus 2026", "IDR", 1_000_000, emptyList(), periodLocked = true),
                        status = PayrollStatus.ReadOnly,
                    ),
                    onIntent = {},
                )
            }
        }

        onNodeWithContentDescription("Periode terkunci. Slip gaji hanya dapat dibaca.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Periode terkunci. Slip gaji hanya dapat dibaca.")))
    }
}
