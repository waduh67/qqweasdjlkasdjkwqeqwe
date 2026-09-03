package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionAdmissionUseCase
import com.duluin.ftth.provisioning.application.port.inbound.ProvisioningExecutionRunner
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.SubscriberAccessIsolationPort
import com.duluin.ftth.provisioning.application.service.CanonicalProvisioningPlanner
import com.duluin.ftth.provisioning.application.service.ImmutableProvisioningPlanService
import com.duluin.ftth.provisioning.application.service.PlanCapability
import com.duluin.ftth.provisioning.application.service.PlanChange
import com.duluin.ftth.provisioning.application.service.PlanCompilationRequest
import com.duluin.ftth.provisioning.application.service.PlanManagementSource
import com.duluin.ftth.provisioning.application.service.PlanObservation
import com.duluin.ftth.provisioning.application.service.PlanTopologyNode
import com.duluin.ftth.provisioning.application.service.ProvisioningSafetyGate
import com.duluin.ftth.provisioning.application.service.ProvisioningWorkflowCommand
import com.duluin.ftth.provisioning.application.service.ProvisioningWorkflowDisposition
import com.duluin.ftth.provisioning.application.service.ProvisioningWorkflowService
import com.duluin.ftth.provisioning.application.service.SubscriberSessionEvidence
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.IntentStatus
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.VlanEncapsulation
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.ManagementEvidenceSourceType
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.PolicyDecision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ProvisioningWorkflowServiceTest {
    private val now = Instant.parse("2026-09-03T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tenantId = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val subscriptionId = UUID.fromString("00000000-0000-7000-8000-000000000002")
    private val intent = ServiceIntent.rehydrate(
        UUID.fromString("00000000-0000-7000-8000-000000000003"), tenantId, subscriptionId,
        UUID.fromString("00000000-0000-7000-8000-000000000004"), VlanEncapsulation.SINGLE_TAG, null,
        IntentStatus.ACTIVE,
    )
    private val olt = DeviceReference(DeviceKind.OLT, UUID.fromString("00000000-0000-7000-8000-000000000010"))
    private val transit = DeviceReference(DeviceKind.SWITCH, UUID.fromString("00000000-0000-7000-8000-000000000011"))
    private val bras = DeviceReference(DeviceKind.BRAS, UUID.fromString("00000000-0000-7000-8000-000000000012"))

    @Test
    fun `create runs upstream to downstream and replay returns one execution`() {
        val fixture = Fixture()

        val first = fixture.workflow.create(command(request()))
        val replay = fixture.workflow.create(command(request()))
        val firstExecution = requireNotNull(first.execution)
        val replayExecution = requireNotNull(replay.execution)

        assertThat(firstExecution.status).isEqualTo(ExecutionStatus.SUCCEEDED)
        assertThat(replayExecution.id).isEqualTo(firstExecution.id)
        assertThat(fixture.runner.forward).containsExactly(
            ProvisionOperation.ENSURE_PPPOE_TERMINATION,
            ProvisionOperation.ENSURE_TAGGED_VLAN,
            ProvisionOperation.ENSURE_ACCESS_PORT,
        )
        assertThat(fixture.executions.values).hasSize(1)
    }

    @Test
    fun `update with changed fresh evidence returns replacement before execution`() {
        val fixture = Fixture()
        val created = fixture.workflow.create(command(request(), key = "create"))
        val changed = request(vlanId = 321)

        val replacement = fixture.workflow.update(
            command(changed, key = "update", expectedHash = created.plan!!.preconditionHash),
        )
        val replacementPlan = requireNotNull(replacement.plan)

        assertThat(replacement.disposition).isEqualTo(ProvisioningWorkflowDisposition.REPLACEMENT_PLAN_REQUIRED)
        assertThat(replacementPlan.revision).isEqualTo(2)
        assertThat(replacement.execution).isNull()
        assertThat(fixture.runner.forward).hasSize(3)

        val applied = fixture.workflow.update(
            command(changed, key = "update", expectedHash = replacementPlan.preconditionHash),
        )
        assertThat(applied.execution!!.status).isEqualTo(ExecutionStatus.SUCCEEDED)
    }

    @Test
    fun `suspend delegates isolation without mutating transport`() {
        val fixture = Fixture()

        val result = fixture.workflow.suspend(subscriptionId)

        assertThat(result.disposition).isEqualTo(ProvisioningWorkflowDisposition.ACCESS_ISOLATED)
        assertThat(fixture.access.isolated).containsExactly(subscriptionId)
        assertThat(fixture.runner.forward).isEmpty()
        assertThat(fixture.plans.values).isEmpty()
    }

    @Test
    fun `delete requires force and successful disconnect for active session`() {
        val fixture = Fixture(activeSessions = 1)

        assertThatThrownBy { fixture.workflow.delete(command(request(PlanChange.DELETE), forceDisconnect = false)) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("ACTIVE_SESSION_REQUIRES_FORCE_DISCONNECT")

        assertThatThrownBy { fixture.workflow.delete(command(request(PlanChange.DELETE), forceDisconnect = true)) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("FORCE_DISCONNECT_FORBIDDEN")

        fixture.access.disconnectSucceeds = false
        assertThatThrownBy {
            fixture.workflow.delete(command(request(PlanChange.DELETE), forceDisconnect = true, forceAuthorized = true))
        }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("ACTIVE_SESSION_DISCONNECT_FAILED")
        assertThat(fixture.runner.forward).isEmpty()

        fixture.access.disconnectSucceeds = true
        val deleted = fixture.workflow.delete(
            command(request(PlanChange.DELETE), forceDisconnect = true, forceAuthorized = true),
        )
        assertThat(deleted.execution!!.status).isEqualTo(ExecutionStatus.SUCCEEDED)
        assertThat(fixture.access.disconnects).containsExactly(subscriptionId, subscriptionId)
    }

    @Test
    fun `delete retains shared bras resources until reference count reaches zero`() {
        val retained = Fixture().also {
            it.workflow.delete(command(request(PlanChange.DELETE, brasReferenceCount = 1), key = "retain"))
        }
        val removed = Fixture().also {
            it.workflow.delete(command(request(PlanChange.DELETE, brasReferenceCount = 0), key = "remove"))
        }

        assertThat(retained.runner.forward).doesNotContain(ProvisionOperation.REMOVE_PPPOE_TERMINATION)
        assertThat(removed.runner.forward.last()).isEqualTo(ProvisionOperation.REMOVE_PPPOE_TERMINATION)
    }

    @Test
    fun `default canary scope rejects more than one subscriber before planning`() {
        val fixture = Fixture()

        assertThatThrownBy { fixture.workflow.create(command(request(), affectedSubscribers = 2)) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("CANARY_SCOPE_EXCEEDED")
        assertThat(fixture.plans.values).isEmpty()
        assertThat(fixture.runner.forward).isEmpty()
    }

    @Test
    fun `stale topology capability and observation evidence is rejected`() {
        val fixture = Fixture()
        val stale = now.minus(Duration.ofMinutes(6))
        val request = request().let {
            it.copy(
                topology = it.topology.map { node -> node.copy(observedAt = stale) },
                capabilities = it.capabilities.map { capability -> capability.copy(observedAt = stale) },
                observations = it.observations.map { observation -> observation.copy(observedAt = stale) },
            )
        }

        assertThatThrownBy { fixture.workflow.create(command(request)) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("STALE_WORKFLOW_EVIDENCE")
        assertThat(fixture.plans.values).isEmpty()
    }

    @Test
    fun `verification failure halts downstream and compensates verified work in reverse`() {
        val fixture = Fixture(failVerificationAt = ProvisionOperation.ENSURE_TAGGED_VLAN)

        val result = fixture.workflow.create(command(request(), key = "failure"))
        val execution = requireNotNull(result.execution)

        assertThat(execution.status).isEqualTo(ExecutionStatus.MANUAL_RECONCILIATION)
        assertThat(execution.detail).isEqualTo("VERIFICATION_MISMATCH")
        assertThat(fixture.runner.forward).containsExactly(
            ProvisionOperation.ENSURE_PPPOE_TERMINATION,
            ProvisionOperation.ENSURE_TAGGED_VLAN,
        )
        assertThat(fixture.runner.compensated).containsExactly(ProvisionOperation.ENSURE_PPPOE_TERMINATION)
        assertThat(fixture.runner.forward).doesNotContain(ProvisionOperation.ENSURE_ACCESS_PORT)
    }

    private fun command(
        request: PlanCompilationRequest,
        key: String = "workflow",
        expectedHash: String? = null,
        forceDisconnect: Boolean = false,
        forceAuthorized: Boolean = false,
        affectedSubscribers: Int = 1,
    ) = ProvisioningWorkflowCommand(
        compilation = request,
        idempotencyKey = key,
        expectedPlanPreconditionHash = expectedHash,
        forceDisconnect = forceDisconnect,
        forceDisconnectAuthorized = forceAuthorized,
        affectedSubscriberIds = buildSet {
            add(request.intent.subscriptionId)
            if (affectedSubscribers > 1) add(UUID.fromString("00000000-0000-7000-8000-000000000099"))
        },
    )

    private fun request(
        change: PlanChange = PlanChange.CREATE,
        brasReferenceCount: Int = 0,
        vlanId: Int = 320,
    ): PlanCompilationRequest {
        val topology = listOf(
            node(olt, ManagedNodeRole.OLT, "pon-1", InterfaceRole.ACCESS),
            node(transit, ManagedNodeRole.ACCESS_SWITCH, "xe-0/0/1", InterfaceRole.TRUNK),
            node(bras, ManagedNodeRole.BRAS, "ae0", InterfaceRole.TRUNK),
        )
        return PlanCompilationRequest(
            intent = intent,
            vlanId = vlanId,
            change = change,
            topology = topology,
            capabilities = listOf(
                PlanCapability(olt, "ZTE", "C320", "1.2", "SSH", setOf("access-vlan"), now),
                PlanCapability(transit, "JUNIPER", "EX", "22", "NETCONF", setOf("tagged-vlan"), now),
                PlanCapability(bras, "MIKROTIK", "CCR", "7", "REST", setOf("pppoe"), now),
            ),
            observations = topology.map { node ->
                PlanObservation(
                    node.device,
                    NormalizedDeviceState.of(NormalizedField.VLAN_ID to NormalizedValue.number(vlanId)),
                    now,
                )
            },
            brasReferenceCount = brasReferenceCount,
        )
    }

    private fun node(
        device: DeviceReference,
        role: ManagedNodeRole,
        interfaceName: String,
        interfaceRole: InterfaceRole,
    ) = PlanTopologyNode(
        device,
        role,
        AdministrativeStatus.ENABLED,
        now,
        PlanManagementSource(
            interfaceName,
            interfaceRole,
            ManagementEvidenceSourceType.TOPOLOGY_OBSERVATION,
            device.id,
            emptySet(),
            emptySet(),
            emptySet(),
            emptySet(),
            emptySet(),
            emptySet(),
        ),
    )

    private inner class Fixture(
        activeSessions: Int = 0,
        failVerificationAt: ProvisionOperation? = null,
    ) {
        val plans = Plans()
        val executions = Executions()
        val access = Access(activeSessions)
        val runner = Runner(plans, executions, failVerificationAt)
        private val planning = ImmutableProvisioningPlanService(CanonicalProvisioningPlanner(), plans, allowingGate())
        private val admission = Admission(plans, executions)
        val workflow = ProvisioningWorkflowService(
            planning,
            admission,
            access,
            runner,
            clock,
        )
    }

    private class Plans : ProvisionPlanRepository {
        val values = linkedMapOf<UUID, ProvisionPlan>()
        override fun save(value: ProvisionPlan) = value.also { values[it.id] = it }
        override fun findById(id: UUID) = values[id]
        override fun findLatestByIntentId(intentId: UUID) =
            values.values.filter { it.intentId == intentId }.maxByOrNull(ProvisionPlan::revision)
    }

    private class Executions : ProvisionExecutionRepository {
        val values = linkedMapOf<UUID, ProvisionExecution>()
        override fun save(value: ProvisionExecution) = value.also { values[it.id] = it }
        override fun findById(id: UUID) = values[id]
        override fun findByIdempotencyKey(key: String) = values.values.firstOrNull { it.idempotencyKey == key }
    }

    private class Admission(
        private val plans: Plans,
        private val executions: Executions,
    ) : ProvisioningExecutionAdmissionUseCase {
        override fun admit(planId: UUID, keySuffix: String): ProvisionExecution {
            val plan = requireNotNull(plans.findById(planId))
            val key = "${plan.intentId}:${plan.revision}:$keySuffix"
            return executions.findByIdempotencyKey(key)
                ?: executions.save(ProvisionExecution.queue(plan.tenantId, plan.intentId, plan.id, key))
        }
    }

    private inner class Access(activeSessions: Int) : SubscriberAccessIsolationPort {
        private var sessions = activeSessions
        var disconnectSucceeds = true
        val isolated = mutableListOf<UUID>()
        val disconnects = mutableListOf<UUID>()

        override fun observe(subscriptionId: UUID) = SubscriberSessionEvidence(sessions, now)

        override fun isolate(subscriptionId: UUID) {
            isolated += subscriptionId
        }

        override fun disconnectActiveSessions(subscriptionId: UUID): SubscriberSessionEvidence {
            disconnects += subscriptionId
            if (disconnectSucceeds) sessions = 0
            return SubscriberSessionEvidence(sessions, now)
        }
    }

    private class Runner(
        private val plans: Plans,
        private val executions: Executions,
        private val failVerificationAt: ProvisionOperation?,
    ) : ProvisioningExecutionRunner {
        val forward = mutableListOf<ProvisionOperation>()
        val compensated = mutableListOf<ProvisionOperation>()

        override fun run(executionId: UUID, ownerId: String): ProvisionExecution {
            val execution = requireNotNull(executions.findById(executionId))
            if (execution.status != ExecutionStatus.QUEUED) return execution
            execution.start()
            val verified = mutableListOf<ProvisionOperation>()
            for (step in requireNotNull(plans.findById(execution.planId)).steps.sortedBy { it.order }) {
                forward += step.operation
                if (step.operation == failVerificationAt) {
                    execution.beginRollback()
                    verified.asReversed().forEach(compensated::add)
                    execution.requireManualReconciliation("VERIFICATION_MISMATCH")
                    return executions.save(execution)
                }
                verified += step.operation
            }
            execution.verify()
            execution.succeed()
            return executions.save(execution)
        }
    }

    private fun allowingGate() = object : ProvisioningSafetyGate {
        override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode) =
            PolicyDecision(true, PolicyCode.AUTO_APPLY_ALLOWED)
    }
}
