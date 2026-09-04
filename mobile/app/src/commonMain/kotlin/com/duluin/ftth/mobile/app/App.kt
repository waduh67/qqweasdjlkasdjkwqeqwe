package com.duluin.ftth.mobile.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.duluin.ftth.mobile.workorders.WorkOrderEffect
import com.duluin.ftth.mobile.workorders.WorkOrderFeature
import com.duluin.ftth.mobile.workorders.WorkOrderIntent
import com.duluin.ftth.mobile.ui.FieldOperationsTheme
import com.duluin.ftth.mobile.workorders.WorkOrderScreen
import com.duluin.ftth.mobile.workorders.WorkOrderStateSaver
import com.duluin.ftth.mobile.workorders.WorkOrderStore
import com.duluin.ftth.mobile.attendance.AttendanceFeature
import com.duluin.ftth.mobile.attendance.AttendanceIntent
import com.duluin.ftth.mobile.attendance.AttendanceScreen
import com.duluin.ftth.mobile.attendance.AttendanceStore
import com.duluin.ftth.mobile.payroll.PayrollFeature
import com.duluin.ftth.mobile.payroll.PayrollIntent
import com.duluin.ftth.mobile.payroll.PayrollScreen
import com.duluin.ftth.mobile.payroll.PayrollStore
import com.duluin.ftth.mobile.domain.Permission
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

interface TechnicianEffectPort {
    fun requestLocationPermission()
    fun announceWorkOrderCompleted()
}

class TechnicianAppDependencies(
    val workOrders: WorkOrderFeature,
    val attendance: AttendanceFeature,
    val payroll: PayrollFeature,
    val permissions: Set<Permission>,
    val effects: TechnicianEffectPort,
    val stateSaver: WorkOrderStateSaver = WorkOrderStateSaver(),
)

@Composable
fun TechnicianApp(dependencies: TechnicianAppDependencies) {
    val scope = rememberCoroutineScope()
    val store = remember(dependencies.workOrders, dependencies.stateSaver) {
        WorkOrderStore(dependencies.workOrders, scope, dependencies.stateSaver)
    }
    val state by store.state.collectAsState()
    val attendanceStore = remember(dependencies.attendance, dependencies.permissions) {
        AttendanceStore(dependencies.attendance, dependencies.permissions, scope)
    }
    val attendanceState by attendanceStore.state.collectAsState()
    val payrollStore = remember(dependencies.payroll, dependencies.permissions) {
        PayrollStore(dependencies.payroll, dependencies.permissions, scope)
    }
    val payrollState by payrollStore.state.collectAsState()

    DisposableEffect(store) {
        onDispose(store::close)
    }
    DisposableEffect(attendanceStore, payrollStore) {
        onDispose {
            attendanceStore.close()
            payrollStore.close()
        }
    }
    LaunchedEffect(store) {
        store.effects.collect { effect ->
            when (effect) {
                WorkOrderEffect.RequestLocationPermission -> dependencies.effects.requestLocationPermission()
                WorkOrderEffect.WorkOrderCompleted -> dependencies.effects.announceWorkOrderCompleted()
            }
        }
    }
    LaunchedEffect(store) {
        store.dispatch(WorkOrderIntent.Load)
    }
    LaunchedEffect(attendanceStore, payrollStore) {
        attendanceStore.dispatch(AttendanceIntent.Load)
        payrollStore.dispatch(PayrollIntent.Load)
    }

    FieldOperationsTheme {
        Column(verticalArrangement = Arrangement.spacedBy(com.duluin.ftth.mobile.ui.FluentTokens.sectionGap)) {
            WorkOrderScreen(state = state, onIntent = { intent -> scope.launch { store.dispatch(intent) } })
            AttendanceScreen(state = attendanceState, onIntent = { intent -> scope.launch { attendanceStore.dispatch(intent) } })
            PayrollScreen(state = payrollState, onIntent = { intent -> scope.launch { payrollStore.dispatch(intent) } })
        }
    }
}
