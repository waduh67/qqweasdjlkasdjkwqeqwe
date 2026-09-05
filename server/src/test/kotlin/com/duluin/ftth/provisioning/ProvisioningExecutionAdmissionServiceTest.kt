package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.VlanPoolRepository
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.service.ProvisioningExecutionAdmissionService
import com.duluin.ftth.provisioning.application.service.ProvisioningSafetyGate
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.PolicyDecision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class ProvisioningExecutionAdmissionServiceTest {
    @Test
    fun `identical idempotency key replays one execution and rejects another plan`() {
        val plans = PlanRepository()
        val executions = ExecutionRepository()
        val firstPlan = validatedPlan().also(plans::save)
        val secondPlan = validatedPlan().also(plans::save)
        val impacts = mock(VlanPoolRepository::class.java).also {
            `when`(it.affectedActiveSubscriberCount(firstPlan.intentId)).thenReturn(1)
            `when`(it.affectedActiveSubscriberCount(secondPlan.intentId)).thenReturn(1)
        }
        val gate = object : ProvisioningSafetyGate {
            override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode) =
                PolicyDecision(true, PolicyCode.AUTO_APPLY_ALLOWED)
        }
        val admission = ProvisioningExecutionAdmissionService(
            plans, executions, gate, ProvisioningRolloutProperties(autoApplyEnabled = true), impacts,
        )

        val first = admission.admit(firstPlan.id, "request-key")
        val replay = admission.admit(firstPlan.id, "request-key")

        assertThat(replay.id).isEqualTo(first.id)
        assertThatThrownBy { admission.admit(secondPlan.id, "request-key") }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("EXECUTION_IDEMPOTENCY_KEY_REUSED")
    }

    @Test
    fun `production admission reloads authoritative plan and rejects before execution persistence`() {
        val plans = PlanRepository()
        val executions = ExecutionRepository()
        val plan = validatedPlan().also(plans::save)
        var evaluatedPlan: ProvisionPlan? = null
        val gate = object : ProvisioningSafetyGate {
            override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision {
                evaluatedPlan = plan
                return PolicyDecision(false, PolicyCode.UNCERTIFIED_CAPABILITY)
            }
        }
        val admission: ProvisioningExecutionAdmissionUseCase =
            ProvisioningExecutionAdmissionService(
                plans, executions, gate, ProvisioningRolloutProperties(autoApplyEnabled = true), blastRadius(plan.intentId, 1),
            )

        assertThatThrownBy { admission.admit(plan.id, "task-12") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage(PolicyCode.UNCERTIFIED_CAPABILITY.name)
        assertThat(evaluatedPlan).isSameAs(plan)
        assertThat(executions.values).isEmpty()
    }

    @Test
    fun `fresh production configuration rejects apply before safety evaluation or persistence`() {
        val plans = PlanRepository()
        val executions = ExecutionRepository()
        val plan = validatedPlan().also(plans::save)
        var evaluated = false
        val gate = object : ProvisioningSafetyGate {
            override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision {
                evaluated = true
                return PolicyDecision(true, PolicyCode.AUTO_APPLY_ALLOWED)
            }
        }
        val admission = ProvisioningExecutionAdmissionService(
            plans, executions, gate, ProvisioningRolloutProperties(), blastRadius(plan.intentId, 1),
        )

        assertThatThrownBy { admission.admit(plan.id, "disabled-auto-apply") }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("PRODUCTION_AUTO_APPLY_DISABLED")
        assertThat(evaluated).isFalse()
        assertThat(executions.values).isEmpty()
    }

    @Test
    fun `admission applies trusted bulk configuration to affected subscriber count`() {
        val plans = PlanRepository()
        val executions = ExecutionRepository()
        val plan = validatedPlan().also(plans::save)
        val gate = object : ProvisioningSafetyGate {
            override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode) =
                PolicyDecision(true, PolicyCode.AUTO_APPLY_ALLOWED)
        }

        val defaultAdmission = ProvisioningExecutionAdmissionService(
            plans, executions, gate, ProvisioningRolloutProperties(autoApplyEnabled = true), blastRadius(plan.intentId, 2),
        )
        assertThatThrownBy { defaultAdmission.admit(plan.id, "bulk-disabled") }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("BULK_EXPANSION_DISABLED")

        val configuredAdmission = ProvisioningExecutionAdmissionService(
            plans,
            executions,
            gate,
            ProvisioningRolloutProperties(
                autoApplyEnabled = true,
                maxAffectedSubscribers = 2,
                bulkExpansionEnabled = true,
            ), blastRadius(plan.intentId, 2),
        )
        assertThat(configuredAdmission.admit(plan.id, "bulk-enabled").planId).isEqualTo(plan.id)
    }

    @Test
    fun `allowed generated plan becomes validated during authoritative admission`() {
        val plans = PlanRepository()
        val executions = ExecutionRepository()
        val generated = ProvisionPlan.generate(
            UuidV7.generate(), UuidV7.generate(), 1,
            listOf(ProvisionStep.create(1, DeviceReference(DeviceKind.BRAS, UuidV7.generate()), ProvisionOperation.ENSURE_PPPOE_TERMINATION, mapOf("vlanId" to "320"))),
        ).also(plans::save)
        val gate = object : ProvisioningSafetyGate {
            override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode) = PolicyDecision(true, PolicyCode.AUTO_APPLY_ALLOWED)
        }
        val admission = ProvisioningExecutionAdmissionService(
            plans, executions, gate, ProvisioningRolloutProperties(autoApplyEnabled = true), blastRadius(generated.intentId, 1),
        )

        val execution = admission.admit(generated.id, "generated-plan")

        assertThat(generated.status.name).isEqualTo("VALIDATED")
        assertThat(execution.planId).isEqualTo(generated.id)
    }

    private fun validatedPlan(): ProvisionPlan = ProvisionPlan.generate(
        UuidV7.generate(),
        UuidV7.generate(),
        1,
        listOf(
            ProvisionStep.create(
                1,
                DeviceReference(DeviceKind.BRAS, UuidV7.generate()),
                ProvisionOperation.ENSURE_PPPOE_TERMINATION,
                mapOf("vlanId" to "320"),
            ),
        ),
    ).also(ProvisionPlan::validate)

    private fun blastRadius(intentId: UUID, count: Int): VlanPoolRepository = mock(VlanPoolRepository::class.java).also {
        `when`(it.affectedActiveSubscriberCount(intentId)).thenReturn(count)
    }

    private class PlanRepository : ProvisionPlanRepository {
        private val values = linkedMapOf<UUID, ProvisionPlan>()
        override fun save(value: ProvisionPlan) = value.also { values[it.id] = it }
        override fun findById(id: UUID) = values[id]
        override fun findLatestByIntentId(intentId: UUID) = values.values.lastOrNull { it.intentId == intentId }
    }

    private class ExecutionRepository : ProvisionExecutionRepository {
        val values = linkedMapOf<UUID, ProvisionExecution>()
        override fun save(value: ProvisionExecution) = value.also { values[it.id] = it }
        override fun findById(id: UUID) = values[id]
        override fun findByIdempotencyKey(key: String) = values.values.firstOrNull { it.idempotencyKey == key }
    }
}
