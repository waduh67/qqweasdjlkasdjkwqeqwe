package com.duluin.ftth.mobile.app

import com.duluin.ftth.mobile.domain.AttendanceCommand
import com.duluin.ftth.mobile.domain.AttendancePort
import com.duluin.ftth.mobile.domain.AttendanceSnapshot
import com.duluin.ftth.mobile.domain.AttendanceSubmission
import com.duluin.ftth.mobile.domain.InMemoryOutbox
import com.duluin.ftth.mobile.domain.OutboxIdentity
import com.duluin.ftth.mobile.domain.Permission
import com.duluin.ftth.mobile.domain.PayslipResult
import com.duluin.ftth.mobile.domain.SecureOutboxPort
import com.duluin.ftth.mobile.domain.SecurePayslipPort
import com.duluin.ftth.mobile.domain.WorkOrder
import com.duluin.ftth.mobile.domain.WorkOrderPort
import com.duluin.ftth.mobile.payroll.PayrollViewModel
import com.duluin.ftth.mobile.workorders.WorkOrderViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotNull

class DiTest {
    @Test
    fun commonModuleResolvesFeatureViewModelsFromPlatformPorts() {
        val koin = startKoin {
            modules(
                commonAppModule,
                module {
                    single { fakePorts() }
                    single<SecureOutboxPort> { FakeSecureOutbox() }
                },
            )
        }
        try {
            assertNotNull(koin.koin.get<WorkOrderViewModel>())
            assertNotNull(koin.koin.get<PayrollViewModel>())
            assertNotNull(koin.koin.get<com.duluin.ftth.mobile.attendance.AttendanceViewModel>())
            assertNotNull(koin.koin.get<TechnicianEffectPort>())
        } finally {
            stopKoin()
        }
    }
}

private fun fakePorts() = TechnicianPlatformPorts(
    workOrders = object : WorkOrderPort {
        override suspend fun list() = Result.success(emptyList<WorkOrder>())
        override suspend fun detail(id: String) = Result.failure<WorkOrder>(IllegalStateException("unused"))
    },
    workOrderOutbox = InMemoryOutbox(),
    attendance = object : AttendancePort {
        override suspend fun snapshot() = Result.failure<AttendanceSnapshot>(IllegalStateException("unused"))
        override suspend fun submit(command: AttendanceCommand): AttendanceSubmission = AttendanceSubmission.Denied
    },
    operationKey = { "test-key" },
    identity = OutboxIdentity("user", "device", "session"),
    payslips = object : SecurePayslipPort {
        override suspend fun personalPayslip() = Result.success<PayslipResult>(PayslipResult.Denied)
    },
    permissions = setOf(Permission.AttendanceSelf, Permission.PayslipSelf),
    effects = object : TechnicianEffectPort {
        override fun requestLocationPermission() = Unit
        override fun announceWorkOrderCompleted() = Unit
    },
)

private class FakeSecureOutbox : SecureOutboxPort {
    override fun enqueue(operation: com.duluin.ftth.mobile.domain.OutboxOperation) = com.duluin.ftth.mobile.domain.EnqueueResult.Accepted
    override fun status() = com.duluin.ftth.mobile.domain.OutboxStatus(0, 0, true)
    override fun enqueueSecure(operation: com.duluin.ftth.mobile.domain.SecureOutboxOperation) = com.duluin.ftth.mobile.domain.EnqueueResult.Accepted
    override fun retry(key: String) = false
    override fun purge(userId: String) = Unit
}
