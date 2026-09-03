package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.service.ProvisioningLifecycleService
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class ProvisioningLifecycleServiceTest {
    private val plan = ProvisionPlan.generate(
        UuidV7.generate(), UuidV7.generate(), 3,
        listOf(ProvisionStep.create(1, DeviceReference(DeviceKind.BRAS, UuidV7.generate()), ProvisionOperation.VERIFY_STATE, emptyMap())),
    ).also(ProvisionPlan::validate)

    @Test
    fun `apply rejects stale revision before admission and replays one idempotent execution`() {
        val plans = Plans(plan)
        val executions = Executions()
        val admission = Admission(plan, executions)
        val service = ProvisioningLifecycleService(
            plans, executions, admission, allowedGate(), ProvisioningRolloutProperties(autoApplyEnabled = true),
        )

        assertThatThrownBy { service.apply(plan.id, 2, "request-1") }
            .isInstanceOf(ConflictException::class.java).hasMessage("STALE_PLAN")
        val first = service.apply(plan.id, 3, "request-1")
        val replay = service.apply(plan.id, 3, "request-1")

        assertThat(replay.id).isEqualTo(first.id)
        assertThat(executions.values).hasSize(1)
    }

    @Test
    fun `cancel transitions only queued execution`() {
        val executions = Executions()
        val queued = executions.save(ProvisionExecution.queue(plan.tenantId, plan.intentId, plan.id, "cancel-key"))
        val service = ProvisioningLifecycleService(
            Plans(plan), executions, Admission(plan, executions), allowedGate(),
            ProvisioningRolloutProperties(autoApplyEnabled = true),
        )

        assertThat(service.cancel(queued.id, 1).status).isEqualTo(ExecutionStatus.CANCELLED)
        assertThatThrownBy { service.cancel(queued.id, 1) }.isInstanceOf(ConflictException::class.java)
    }

    private class Plans(private val plan: ProvisionPlan) : ProvisionPlanRepository {
        override fun save(value: ProvisionPlan) = value
        override fun findById(id: UUID) = plan.takeIf { it.id == id }
        override fun findLatestByIntentId(intentId: UUID) = plan.takeIf { it.intentId == intentId }
    }

    private class Executions : ProvisionExecutionRepository {
        val values = linkedMapOf<UUID, ProvisionExecution>()
        override fun save(value: ProvisionExecution) = value.also { values[it.id] = it }
        override fun findById(id: UUID) = values[id]
        override fun findByIdempotencyKey(key: String) = values.values.firstOrNull { it.idempotencyKey == key }
    }

    private class Admission(
        private val plan: ProvisionPlan,
        private val executions: Executions,
    ) : ProvisioningExecutionAdmissionUseCase {
        override fun admit(planId: UUID, keySuffix: String, affectedSubscriberCount: Int): ProvisionExecution {
            return executions.findByIdempotencyKey(keySuffix)
                ?: executions.save(ProvisionExecution.queue(plan.tenantId, plan.intentId, plan.id, keySuffix))
        }
    }

    private fun allowedGate() = object : com.duluin.ftth.provisioning.application.service.ProvisioningSafetyGate {
        override fun evaluate(
            plan: ProvisionPlan,
            mode: com.duluin.ftth.provisioning.domain.policy.ExecutionMode,
        ) = com.duluin.ftth.provisioning.domain.policy.PolicyDecision(true, com.duluin.ftth.provisioning.domain.policy.PolicyCode.DRY_RUN_ALLOWED)
    }
}
