package com.duluin.ftth.mobile.app

import com.duluin.ftth.mobile.attendance.AttendanceFeature
import com.duluin.ftth.mobile.attendance.AttendanceViewModel
import com.duluin.ftth.mobile.domain.AttendancePort
import com.duluin.ftth.mobile.domain.ObserveWorkOrders
import com.duluin.ftth.mobile.domain.Outbox
import com.duluin.ftth.mobile.domain.OutboxIdentity
import com.duluin.ftth.mobile.domain.Permission
import com.duluin.ftth.mobile.domain.SecurePayslipPort
import com.duluin.ftth.mobile.domain.WorkOrderPort
import com.duluin.ftth.mobile.mvi.MviStateSaver
import com.duluin.ftth.mobile.payroll.PayrollFeature
import com.duluin.ftth.mobile.payroll.PayrollViewModel
import com.duluin.ftth.mobile.workorders.WorkOrderFeature
import com.duluin.ftth.mobile.workorders.WorkOrderStateSaver
import com.duluin.ftth.mobile.workorders.WorkOrderUiState
import com.duluin.ftth.mobile.workorders.WorkOrderViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlinx.coroutines.Dispatchers

data class TechnicianPlatformPorts(
    val workOrders: WorkOrderPort,
    val workOrderOutbox: Outbox,
    val attendance: AttendancePort,
    val operationKey: () -> String,
    val identity: OutboxIdentity,
    val payslips: SecurePayslipPort,
    val permissions: Set<Permission>,
    val effects: TechnicianEffectPort,
)

val commonAppModule: Module = module {
    single<Set<Permission>> { get<TechnicianPlatformPorts>().permissions }
    single<Outbox> { get<TechnicianPlatformPorts>().workOrderOutbox }
    single<WorkOrderPort> { get<TechnicianPlatformPorts>().workOrders }
    single<AttendancePort> { get<TechnicianPlatformPorts>().attendance }
    single<SecurePayslipPort> { get<TechnicianPlatformPorts>().payslips }
    single<TechnicianEffectPort> { get<TechnicianPlatformPorts>().effects }
    single { get<TechnicianPlatformPorts>().operationKey }
    single { get<TechnicianPlatformPorts>().identity }
    single { ObserveWorkOrders(get()) }
    factoryOf(::WorkOrderFeature)
    factoryOf(::AttendanceFeature)
    factoryOf(::PayrollFeature)
    factory<MviStateSaver<WorkOrderUiState>> { WorkOrderStateSaver() }
    viewModel { WorkOrderViewModel(get(), get(), Dispatchers.Default) }
    viewModel { AttendanceViewModel(get(), get(), Dispatchers.Default) }
    viewModel { PayrollViewModel(get(), get(), Dispatchers.Default) }
}
