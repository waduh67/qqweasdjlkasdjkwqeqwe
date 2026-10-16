package com.karuhun.ftth.mobile.app

import com.karuhun.ftth.mobile.domain.AttendanceCommand
import com.karuhun.ftth.mobile.domain.AttendancePort
import com.karuhun.ftth.mobile.domain.AttendanceSnapshot
import com.karuhun.ftth.mobile.domain.AttendanceSubmission
import com.karuhun.ftth.mobile.domain.InMemoryOutbox
import com.karuhun.ftth.mobile.domain.OutboxIdentity
import com.karuhun.ftth.mobile.domain.Permission
import com.karuhun.ftth.mobile.domain.PayslipResult
import com.karuhun.ftth.mobile.domain.SecureOutboxPort
import com.karuhun.ftth.mobile.domain.SecurePayslipPort
import com.karuhun.ftth.mobile.domain.WorkOrder
import com.karuhun.ftth.mobile.domain.WorkOrderPort
import com.karuhun.ftth.mobile.payroll.PayrollViewModel
import com.karuhun.ftth.mobile.workorders.WorkOrderViewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotNull
import com.karuhun.ftth.mobile.data.MaterialHttpPort
import com.karuhun.ftth.mobile.data.MaterialHttpResponse
import com.karuhun.ftth.mobile.domain.MaterialSession
import com.karuhun.ftth.mobile.domain.MaterialSessionPort
import com.karuhun.ftth.mobile.materials.MaterialViewModel
import kotlinx.coroutines.flow.MutableStateFlow

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
            assertNotNull(koin.koin.get<com.karuhun.ftth.mobile.attendance.AttendanceViewModel>())
            assertNotNull(koin.koin.get<TechnicianEffectPort>())
            assertNotNull(koin.koin.get<com.karuhun.ftth.mobile.domain.MaterialPort>())
            assertNotNull(koin.koin.get<MaterialViewModel>())
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
    materialHttp = object : MaterialHttpPort {
        override suspend fun get(path: String, session: MaterialSession) = MaterialHttpResponse(503, "{}")
        override suspend fun post(path: String, body: String, idempotencyKey: String, session: MaterialSession) = MaterialHttpResponse(503, "{}")
    },
    materialSession = object : MaterialSessionPort {
        override val state = MutableStateFlow<MaterialSession?>(null)
        override val connectivity = MutableStateFlow(false)
    },
)

private class FakeSecureOutbox : SecureOutboxPort {
    override fun enqueue(operation: com.karuhun.ftth.mobile.domain.OutboxOperation) = com.karuhun.ftth.mobile.domain.EnqueueResult.Accepted
    override fun status() = com.karuhun.ftth.mobile.domain.OutboxStatus(0, 0, true)
    override fun enqueueSecure(operation: com.karuhun.ftth.mobile.domain.SecureOutboxOperation) = com.karuhun.ftth.mobile.domain.EnqueueResult.Accepted
    override fun retry(key: String) = false
    override fun purge(userId: String) = Unit
    override fun entries(identity: com.karuhun.ftth.mobile.domain.OutboxIdentity, namespace: String) = emptyList<com.karuhun.ftth.mobile.domain.SecureOutboxEntry>()
    override fun mark(identity: com.karuhun.ftth.mobile.domain.OutboxIdentity, namespace: String, key: String, state: com.karuhun.ftth.mobile.domain.SecureDeliveryState) = Unit
    override fun complete(identity: com.karuhun.ftth.mobile.domain.OutboxIdentity, namespace: String, key: String) = Unit
}
