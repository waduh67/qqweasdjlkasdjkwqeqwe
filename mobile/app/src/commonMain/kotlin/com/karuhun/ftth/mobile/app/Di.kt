package com.karuhun.ftth.mobile.app

import com.karuhun.ftth.mobile.attendance.AttendanceFeature
import com.karuhun.ftth.mobile.attendance.AttendanceViewModel
import com.karuhun.ftth.mobile.domain.AttendancePort
import com.karuhun.ftth.mobile.domain.ObserveWorkOrders
import com.karuhun.ftth.mobile.domain.Outbox
import com.karuhun.ftth.mobile.domain.OutboxIdentity
import com.karuhun.ftth.mobile.domain.Permission
import com.karuhun.ftth.mobile.domain.SecurePayslipPort
import com.karuhun.ftth.mobile.domain.WorkOrderPort
import com.karuhun.ftth.mobile.mvi.MviStateSaver
import com.karuhun.ftth.mobile.payroll.PayrollFeature
import com.karuhun.ftth.mobile.payroll.PayrollViewModel
import com.karuhun.ftth.mobile.workorders.WorkOrderFeature
import com.karuhun.ftth.mobile.workorders.WorkOrderStateSaver
import com.karuhun.ftth.mobile.workorders.WorkOrderUiState
import com.karuhun.ftth.mobile.workorders.WorkOrderViewModel
import com.karuhun.ftth.mobile.domain.MaterialPort
import com.karuhun.ftth.mobile.domain.MaterialSessionPort
import com.karuhun.ftth.mobile.data.MaterialHttpPort
import com.karuhun.ftth.mobile.data.MaterialRepository
import com.karuhun.ftth.mobile.materials.MaterialFeature
import com.karuhun.ftth.mobile.materials.MaterialViewModel
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
    val materialHttp: MaterialHttpPort,
    val materialSession: MaterialSessionPort,
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
    single<MaterialSessionPort> { get<TechnicianPlatformPorts>().materialSession }
    single<MaterialPort> { MaterialRepository(get<TechnicianPlatformPorts>().materialHttp, get(), get(), get()) }
    factoryOf(::MaterialFeature)
    factoryOf(::WorkOrderFeature)
    factoryOf(::AttendanceFeature)
    factoryOf(::PayrollFeature)
    factory<MviStateSaver<WorkOrderUiState>> { WorkOrderStateSaver() }
    viewModel { WorkOrderViewModel(get(), get(), Dispatchers.Default) }
    viewModel { AttendanceViewModel(get(), get(), Dispatchers.Default) }
    viewModel { PayrollViewModel(get(), get(), Dispatchers.Default) }
    viewModel { MaterialViewModel(get(), Dispatchers.Default) }
}
