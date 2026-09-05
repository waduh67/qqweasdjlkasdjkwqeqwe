package com.duluin.ftth.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.duluin.ftth.mobile.attendance.AttendanceIntent
import com.duluin.ftth.mobile.attendance.AttendanceScreen
import com.duluin.ftth.mobile.attendance.AttendanceViewModel
import com.duluin.ftth.mobile.payroll.PayrollIntent
import com.duluin.ftth.mobile.payroll.PayrollScreen
import com.duluin.ftth.mobile.payroll.PayrollViewModel
import com.duluin.ftth.mobile.ui.FieldOperationsTheme
import com.duluin.ftth.mobile.workorders.WorkOrderEffect
import com.duluin.ftth.mobile.workorders.WorkOrderIntent
import com.duluin.ftth.mobile.workorders.WorkOrderScreen
import com.duluin.ftth.mobile.workorders.WorkOrderViewModel
import org.koin.core.module.Module

interface TechnicianEffectPort {
    fun requestLocationPermission()
    fun announceWorkOrderCompleted()
}

@Composable
fun TechnicianApp(platformModule: Module) {
    KoinApplication(application = { modules(commonAppModule, platformModule) }) {
        TechnicianAppContent()
    }
}

@Composable
private fun TechnicianAppContent(
    workOrders: WorkOrderViewModel = koinViewModel(),
    attendance: AttendanceViewModel = koinViewModel(),
    payroll: PayrollViewModel = koinViewModel(),
    effects: TechnicianEffectPort = koinInject(),
) {
    val workOrderState by workOrders.state.collectAsStateWithLifecycle()
    val attendanceState by attendance.state.collectAsStateWithLifecycle()
    val payrollState by payroll.state.collectAsStateWithLifecycle()

    LaunchedEffect(workOrders) {
        workOrders.effects.collect { effect ->
            when (effect) {
                WorkOrderEffect.RequestLocationPermission -> effects.requestLocationPermission()
                WorkOrderEffect.WorkOrderCompleted -> effects.announceWorkOrderCompleted()
            }
        }
    }
    LaunchedEffect(workOrders, attendance, payroll) {
        workOrders.accept(WorkOrderIntent.Load)
        attendance.accept(AttendanceIntent.Load)
        payroll.accept(PayrollIntent.Load)
    }

    FieldOperationsTheme {
        Column(verticalArrangement = Arrangement.spacedBy(com.duluin.ftth.mobile.ui.FluentTokens.sectionGap)) {
            WorkOrderScreen(workOrderState, workOrders::accept)
            AttendanceScreen(attendanceState, attendance::accept)
            PayrollScreen(payrollState, payroll::accept)
        }
    }
}
