package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.DeviceCircuitBreakerRepository
import com.duluin.ftth.provisioning.application.port.outbound.DeviceLeaseRepository
import com.duluin.ftth.provisioning.application.port.outbound.ExecutionStepRepository
import com.duluin.ftth.provisioning.application.port.outbound.FencedExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepAttemptRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepSnapshotRepository
import com.duluin.ftth.provisioning.application.service.DeviceApplyResult
import com.duluin.ftth.provisioning.application.service.DeviceStateObservation
import com.duluin.ftth.provisioning.application.service.DispatchableProvisioningWork
import com.duluin.ftth.provisioning.application.service.ProvisioningDeviceGateway
import com.duluin.ftth.provisioning.application.service.ProvisioningExecutionEngine
import com.duluin.ftth.provisioning.application.service.ProvisioningSafetyGate
import com.duluin.ftth.provisioning.application.service.SafetyPlanAttributes
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningSafetyAdmissionIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var plans: ProvisionPlanRepository
    @Autowired private lateinit var executions: ProvisionExecutionRepository
    @Autowired private lateinit var leases: DeviceLeaseRepository
    @Autowired private lateinit var fencedWrites: FencedExecutionRepository
    @Autowired private lateinit var steps: ExecutionStepRepository
    @Autowired private lateinit var attempts: StepAttemptRepository
    @Autowired private lateinit var snapshots: StepSnapshotRepository
    @Autowired private lateinit var circuits: DeviceCircuitBreakerRepository
    @Autowired private lateinit var safetyGate: ProvisioningSafetyGate
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `missing production evidence creates zero execution attempt or collector command rows`() {
        val tenantId = tenantApi.ensureTenant("admission-${UUID.randomUUID()}", "admission").id
        val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val plan = ProvisionPlan.generate(
            tenantId,
            UuidV7.generate(),
            1,
            listOf(
                ProvisionStep.create(
                    1,
                    device,
                    ProvisionOperation.ENSURE_PPPOE_TERMINATION,
                    mapOf(
                        "vlanId" to "320",
                        SafetyPlanAttributes.VENDOR to "MIKROTIK",
                        SafetyPlanAttributes.MODEL to "CCR2004",
                        SafetyPlanAttributes.FIRMWARE to "7.20.2",
                        SafetyPlanAttributes.TRANSPORT to "HTTPS_REST",
                    ),
                ),
            ),
        ).also { it.validate() }
        val engine = ProvisioningExecutionEngine(
            plans,
            executions,
            leases,
            fencedWrites,
            steps,
            attempts,
            snapshots,
            circuits,
            RejectingDeviceGateway,
            safetyGate,
            Clock.systemUTC(),
            { },
        )

        assertThatThrownBy { asTenant(tenantId) { engine.enqueue(plan, "missing-evidence") } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage(PolicyCode.MISSING_CAPABILITY_EVIDENCE.name)

        assertThat(asTenant(tenantId) { count("provisioning_execution", "plan_id", plan.id) }).isZero()
        assertThat(asTenant(tenantId) { countAttempts(plan.id) }).isZero()
        assertThat(asTenant(tenantId) { countReceipts(plan.id.toString()) }).isZero()
    }

    private fun count(table: String, column: String, value: UUID): Long =
        (em.createNativeQuery("SELECT count(*) FROM $table WHERE $column = :value")
            .setParameter("value", value).singleResult as Number).toLong()

    private fun countAttempts(planId: UUID): Long =
        (em.createNativeQuery(
            """SELECT count(*) FROM provisioning_step_attempt attempt
               JOIN provisioning_execution_step step ON step.id = attempt.execution_step_id
               JOIN provisioning_execution execution ON execution.id = step.execution_id
               WHERE execution.plan_id = :plan""",
        ).setParameter("plan", planId).singleResult as Number).toLong()

    private fun countReceipts(planId: String): Long =
        (em.createNativeQuery("SELECT count(*) FROM provisioning_collector_result_receipt WHERE plan_id = :plan")
            .setParameter("plan", planId).singleResult as Number).toLong()

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    private object RejectingDeviceGateway : ProvisioningDeviceGateway {
        override fun observe(work: DispatchableProvisioningWork): DeviceStateObservation = error("DEVICE_COMMAND_NOT_ALLOWED")
        override fun apply(work: DispatchableProvisioningWork): DeviceApplyResult = error("DEVICE_COMMAND_NOT_ALLOWED")
        override fun compensate(work: DispatchableProvisioningWork, before: NormalizedDeviceState): DeviceApplyResult =
            error("DEVICE_COMMAND_NOT_ALLOWED")
    }
}
