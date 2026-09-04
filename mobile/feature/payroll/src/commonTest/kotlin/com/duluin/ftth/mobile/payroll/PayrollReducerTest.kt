package com.duluin.ftth.mobile.payroll

import com.duluin.ftth.mobile.domain.Permission
import com.duluin.ftth.mobile.domain.PersonalPayslip
import kotlin.test.Test
import kotlin.test.assertEquals

class PayrollReducerTest {
    @Test
    fun lockedPersonalPayslipIsVisibleButContainsNoPayrollMutationActions() {
        val payslip = PersonalPayslip("Agustus 2026", "IDR", 1_000_000, emptyList(), periodLocked = true)
        val transition = PayrollReducer().reduce(
            PayrollUiState(permissions = setOf(Permission.PayslipSelf)),
            PayrollIntent.Loaded(payslip),
        )

        assertEquals(PayrollStatus.ReadOnly, transition.state.status)
        assertEquals(emptyList(), transition.actions)
    }

    @Test
    fun peerSalaryRequestIsDeniedBeforeItReachesTheSecurePort() {
        val transition = PayrollReducer().reduce(PayrollUiState(), PayrollIntent.Load)

        assertEquals(PayrollStatus.PermissionDenied, transition.state.status)
        assertEquals(emptyList(), transition.actions)
    }
}
