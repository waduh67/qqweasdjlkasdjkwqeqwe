package com.karuhun.ftth.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import com.karuhun.ftth.mobile.attendance.AttendanceIntent
import com.karuhun.ftth.mobile.attendance.AttendanceScreen
import com.karuhun.ftth.mobile.attendance.AttendanceViewModel
import com.karuhun.ftth.mobile.payroll.PayrollIntent
import com.karuhun.ftth.mobile.payroll.PayrollScreen
import com.karuhun.ftth.mobile.payroll.PayrollViewModel
import com.karuhun.ftth.mobile.ui.FieldOperationsTheme
import com.karuhun.ftth.mobile.workorders.WorkOrderEffect
import com.karuhun.ftth.mobile.workorders.WorkOrderIntent
import com.karuhun.ftth.mobile.workorders.WorkOrderScreen
import com.karuhun.ftth.mobile.workorders.WorkOrderViewModel
import com.karuhun.ftth.mobile.materials.MaterialScreen
import com.karuhun.ftth.mobile.materials.MaterialViewModel
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
    materials: MaterialViewModel = koinViewModel(),
    effects: TechnicianEffectPort = koinInject(),
) {
    val workOrderState by workOrders.state.collectAsStateWithLifecycle()
    val attendanceState by attendance.state.collectAsStateWithLifecycle()
    val payrollState by payroll.state.collectAsStateWithLifecycle()
    val materialState by materials.state.collectAsStateWithLifecycle()

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
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(com.karuhun.ftth.mobile.ui.FluentTokens.sectionGap)) {
            WorkOrderScreen(workOrderState, workOrders::accept)
            MaterialScreen(materialState, materials::accept)
            AttendanceScreen(attendanceState, attendance::accept)
            PayrollScreen(payrollState, payroll::accept)
        }
    }
}
