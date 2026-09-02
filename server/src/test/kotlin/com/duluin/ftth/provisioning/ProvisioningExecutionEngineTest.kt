package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.port.outbound.DeviceCircuitBreakerRepository
import com.duluin.ftth.provisioning.application.port.outbound.DeviceLeaseRepository
import com.duluin.ftth.provisioning.application.port.outbound.ExecutionStepRepository
import com.duluin.ftth.provisioning.application.port.outbound.FencedExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepAttemptRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepSnapshotRepository
import com.duluin.ftth.provisioning.application.service.DeviceApplyResult
import com.duluin.ftth.provisioning.application.service.DeviceFailureKind
import com.duluin.ftth.provisioning.application.service.DeviceOperationException
import com.duluin.ftth.provisioning.application.service.DeviceStateObservation
import com.duluin.ftth.provisioning.application.service.DispatchableProvisioningWork
import com.duluin.ftth.provisioning.application.service.ExecutionPolicy
import com.duluin.ftth.provisioning.application.service.ProvisioningDeviceGateway
import com.duluin.ftth.provisioning.application.service.ProvisioningExecutionEngine
import com.duluin.ftth.provisioning.application.service.ProvisioningSafetyGate
import com.duluin.ftth.provisioning.application.service.RetrySleeper
import com.duluin.ftth.provisioning.application.service.SafetyGateScope
import com.duluin.ftth.provisioning.application.service.SimulatedProcessCrash
import com.duluin.ftth.provisioning.domain.model.AttemptStatus
import com.duluin.ftth.provisioning.domain.model.DeviceCircuitBreaker
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceLease
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.ExecutionStatus
import com.duluin.ftth.provisioning.domain.model.ExecutionStep
import com.duluin.ftth.provisioning.domain.model.ExecutionStepStatus
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.NormalizedStateHash
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.provisioning.domain.model.StepAttempt
import com.duluin.ftth.provisioning.domain.model.StepSnapshot
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.provisioning.domain.policy.PolicyCode
import com.duluin.ftth.provisioning.domain.policy.PolicyDecision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ProvisioningExecutionEngineTest {
    @Test
    fun `enqueue safety denial writes zero execution step attempt or command state`() {
        val fixture = Fixture()
        fixture.safety.decision = PolicyDecision(false, PolicyCode.UNCERTIFIED_CAPABILITY)

        assertThatThrownBy { fixture.engine.enqueue(fixture.plan, "denied") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage(PolicyCode.UNCERTIFIED_CAPABILITY.name)

        assertThat(fixture.executions.values).isEmpty()
        assertThat(fixture.attempts.values).isEmpty()
        assertThat(fixture.gateway.applyCount).isEmpty()
    }

    @Test
    fun `run rereads safety before creating execution steps attempts or commands`() {
        val fixture = Fixture()
        val execution = fixture.engine.enqueue(fixture.plan, "revoked-after-enqueue")
        fixture.safety.decision = PolicyDecision(false, PolicyCode.UNCERTIFIED_CAPABILITY)

        assertThatThrownBy { fixture.engine.run(execution.id, "worker-a") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage(PolicyCode.UNCERTIFIED_CAPABILITY.name)

        assertThat(fixture.steps.values).isEmpty()
        assertThat(fixture.attempts.values).isEmpty()
        assertThat(fixture.gateway.applyCount).isEmpty()
    }

    @Test
    fun `per command safety denial creates no attempt and releases acquired lease`() {
        val fixture = Fixture()
        fixture.safety.rejectAtEvaluation = 3
        val execution = fixture.engine.enqueue(fixture.plan, "deny-before-command")

        val rejected = fixture.engine.run(execution.id, "worker-a")

        assertThat(rejected.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(fixture.attempts.values).isEmpty()
        assertThat(fixture.gateway.applyCount).isEmpty()
        assertThat(
            fixture.leases.acquire(
                fixture.tenantId,
                fixture.bras,
                execution.id,
                "worker-b",
                fixture.clock.instant(),
                Duration.ofSeconds(30),
            ),
        ).isNotNull
    }

    @Test
    fun `forward certification expiry after a verified step triggers management safe rollback`() {
        val fixture = Fixture()
        fixture.safety.rejectAtEvaluation = 6
        val execution = fixture.engine.enqueue(fixture.plan, "expire-after-first-step")

        fixture.engine.run(execution.id, "worker-a")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.ROLLED_BACK)
        assertThat(fixture.gateway.compensated).containsExactly(fixture.bras)
        assertThat(fixture.gateway.applyCount[fixture.olt]).isNull()
    }

    @Test
    fun `expired worker result is fenced after another worker takes over`() {
        val fixture = Fixture(policy = ExecutionPolicy(leaseDuration = Duration.ofSeconds(5)))
        val execution = fixture.engine.enqueue(fixture.plan, "stale-worker")
        fixture.gateway.afterApply = { work ->
            if (work.device == fixture.bras) {
                fixture.fencedWrites.beforeCommit = {
                    fixture.clock.advance(Duration.ofSeconds(6))
                    assertThat(
                        fixture.leases.acquire(
                            fixture.tenantId,
                            fixture.bras,
                            execution.id,
                            "worker-b",
                            fixture.clock.instant(),
                            Duration.ofSeconds(30),
                        ),
                    ).isNotNull
                }
            }
        }

        fixture.engine.run(execution.id, "worker-a")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.RUNNING)
        assertThat(fixture.steps.findByExecutionId(execution.id).first().status)
            .isEqualTo(ExecutionStepStatus.APPLY_DISPATCHED)
        assertThat(
            fixture.attempts.findByExecutionStepId(fixture.steps.findByExecutionId(execution.id).first().id)
                .first { it.phase == ExecutionPhase.APPLY }.status,
        ).isEqualTo(AttemptStatus.DISPATCHED)

        fixture.gateway.afterApply = null
        fixture.engine().run(execution.id, "worker-b")
        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.SUCCEEDED)
        assertThat(fixture.gateway.applyCount.getValue(fixture.bras)).isEqualTo(1)
    }

    @Test
    fun `late synchronous result past attempt deadline cannot persist success`() {
        val fixture = Fixture(
            policy = ExecutionPolicy(
                maxAttempts = 1,
                attemptTimeout = Duration.ofSeconds(5),
                leaseDuration = Duration.ofSeconds(30),
                circuitFailureThreshold = 10,
            ),
        )
        val execution = fixture.engine.enqueue(fixture.plan, "late-result")
        fixture.gateway.afterApply = { work ->
            if (work.device == fixture.bras) fixture.clock.advance(Duration.ofSeconds(6))
        }

        fixture.engine.run(execution.id, "worker")

        assertThat(fixture.executions.findById(execution.id)!!.status)
            .isEqualTo(ExecutionStatus.MANUAL_RECONCILIATION)
        val applyAttempt = fixture.attempts.findByExecutionStepId(fixture.steps.findByExecutionId(execution.id).first().id)
            .first { it.phase == ExecutionPhase.APPLY }
        assertThat(applyAttempt.status).isEqualTo(AttemptStatus.TRANSIENT_FAILURE)
        assertThat(applyAttempt.errorCode).isEqualTo("DEADLINE_EXCEEDED")
    }

    @Test
    fun `superseded plan resumes compensating from snapshots without false rollback success`() {
        val fixture = Fixture()
        fixture.gateway.applyFailures[fixture.olt] = ArrayDeque(
            listOf(DeviceOperationException("REJECTED", DeviceFailureKind.PERMANENT)),
        )
        fixture.gateway.crashAfterCompensateFor += fixture.bras
        val execution = fixture.engine.enqueue(fixture.plan, "compensating-crash")

        assertThatThrownBy { fixture.engine.run(execution.id, "worker-a") }
            .isInstanceOf(SimulatedProcessCrash::class.java)
        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.ROLLING_BACK)
        assertThat(fixture.steps.findByExecutionId(execution.id).first().status)
            .isEqualTo(ExecutionStepStatus.COMPENSATING)
        assertThat(
            fixture.leases.acquire(
                fixture.tenantId,
                fixture.bras,
                execution.id,
                "worker-b",
                fixture.clock.instant(),
                Duration.ofSeconds(30),
            ),
        ).isNull()

        fixture.plan.supersede()
        fixture.plans.save(fixture.plan)
        fixture.clock.advance(Duration.ofMinutes(1))
        fixture.safety.decision = PolicyDecision(false, PolicyCode.STALE_CERTIFICATION_EVIDENCE)
        fixture.engine().run(execution.id, "worker-b")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.ROLLED_BACK)
        assertThat(fixture.steps.findByExecutionId(execution.id).first().status)
            .isEqualTo(ExecutionStepStatus.COMPENSATED)
    }

    @Test
    fun `persisted failed step resumes to failure with zero gateway mutation`() {
        val fixture = Fixture()
        val execution = fixture.engine.enqueue(fixture.plan, "failed-resume")
        val planStep = fixture.plan.steps.first()
        fixture.steps.save(
            ExecutionStep.pending(fixture.tenantId, execution.id, planStep.id, planStep.order, planStep.device),
        ).fail("PERMANENT_REJECTION")

        fixture.engine().run(execution.id, "worker")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(fixture.gateway.applyCount).isEmpty()
    }

    @Test
    fun `compensation failure releases lease for immediate reacquisition`() {
        val fixture = Fixture()
        fixture.gateway.applyFailures[fixture.olt] = ArrayDeque(
            listOf(DeviceOperationException("REJECTED", DeviceFailureKind.PERMANENT)),
        )
        fixture.gateway.compensateFailures[fixture.bras] = ArrayDeque(
            listOf(DeviceOperationException("ROLLBACK_FAILED", DeviceFailureKind.PERMANENT)),
        )
        val execution = fixture.engine.enqueue(fixture.plan, "compensation-release")

        fixture.engine.run(execution.id, "worker-a")

        assertThat(fixture.executions.findById(execution.id)!!.status)
            .isEqualTo(ExecutionStatus.MANUAL_RECONCILIATION)
        assertThat(
            fixture.leases.acquire(
                fixture.tenantId,
                fixture.bras,
                execution.id,
                "worker-b",
                fixture.clock.instant(),
                Duration.ofSeconds(30),
            ),
        ).isNotNull
    }

    @Test
    fun `run rejects plan superseded after enqueue`() {
        val fixture = Fixture()
        val execution = fixture.engine.enqueue(fixture.plan, "superseded")
        fixture.plan.supersede()
        fixture.plans.save(fixture.plan)

        assertThatThrownBy { fixture.engine.run(execution.id, "worker") }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("PLAN_NOT_VALIDATED")
        assertThat(fixture.gateway.applyCount).isEmpty()
    }

    @Test
    fun `crash before dispatch resumes to success and duplicate acknowledgement is ignored`() {
        val fixture = Fixture()
        val execution = fixture.engine.enqueue(fixture.plan, "create")

        val restarted = fixture.engine()
        restarted.run(execution.id, "worker-a")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.SUCCEEDED)
        assertThat(fixture.steps.findByExecutionId(execution.id).map { it.status })
            .containsOnly(ExecutionStepStatus.VERIFIED)
        assertThat(fixture.gateway.applyCount.values.sum()).isEqualTo(2)
        val acknowledged = fixture.steps.findByExecutionId(execution.id)
            .flatMap { fixture.attempts.findByExecutionStepId(it.id) }
            .first { it.phase == ExecutionPhase.APPLY }
        assertThat(restarted.ingestAcknowledgement(acknowledged.id, AttemptStatus.SUCCEEDED, null)).isFalse()
    }

    @Test
    fun `apply before acknowledgement resumes without applying twice after lease expiry`() {
        val fixture = Fixture()
        val execution = fixture.engine.enqueue(fixture.plan, "lost-ack")
        fixture.gateway.crashAfterApplyFor += fixture.bras

        assertThatThrownBy { fixture.engine.run(execution.id, "worker-a") }
            .isInstanceOf(SimulatedProcessCrash::class.java)
        assertThat(fixture.steps.findByExecutionId(execution.id).first().status)
            .isEqualTo(ExecutionStepStatus.APPLY_DISPATCHED)

        fixture.clock.advance(Duration.ofMinutes(1))
        fixture.engine().run(execution.id, "worker-b")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.SUCCEEDED)
        assertThat(fixture.gateway.applyCount.getValue(fixture.bras)).isEqualTo(1)
    }

    @Test
    fun `nack persisted around restart is idempotent and resume still converges`() {
        val fixture = Fixture()
        val execution = fixture.engine.enqueue(fixture.plan, "nack-restart")
        fixture.gateway.crashAfterApplyFor += fixture.bras
        assertThatThrownBy { fixture.engine.run(execution.id, "worker-a") }
            .isInstanceOf(SimulatedProcessCrash::class.java)
        val dispatched = fixture.attempts.findByExecutionStepId(fixture.steps.findByExecutionId(execution.id).first().id)
            .first { it.phase == ExecutionPhase.APPLY }

        val restarted = fixture.engine()
        assertThat(restarted.ingestAcknowledgement(dispatched.id, AttemptStatus.TRANSIENT_FAILURE, "ACK_LOST"))
            .isTrue()
        assertThat(restarted.ingestAcknowledgement(dispatched.id, AttemptStatus.TRANSIENT_FAILURE, "ACK_LOST"))
            .isFalse()
        fixture.clock.advance(Duration.ofMinutes(1))
        restarted.run(execution.id, "worker-b")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.SUCCEEDED)
        assertThat(fixture.gateway.applyCount.getValue(fixture.bras)).isEqualTo(1)
    }

    @Test
    fun `stale precondition never dispatches a blind write`() {
        val fixture = Fixture()
        fixture.gateway.drift(fixture.bras)
        val execution = fixture.engine.enqueue(fixture.plan, "stale-precondition")

        fixture.engine.run(execution.id, "worker")

        assertThat(fixture.executions.findById(execution.id)!!.status)
            .isEqualTo(ExecutionStatus.MANUAL_RECONCILIATION)
        assertThat(fixture.executions.findById(execution.id)!!.detail).isEqualTo("STALE_PRECONDITION")
        assertThat(fixture.gateway.applyCount).isEmpty()
    }

    @Test
    fun `verification mismatch is never retried and requires manual reconciliation`() {
        val fixture = Fixture()
        val execution = fixture.engine.enqueue(fixture.plan, "verify-mismatch")
        fixture.gateway.verificationMismatchFor += fixture.olt

        fixture.engine.run(execution.id, "worker-a")

        assertThat(fixture.executions.findById(execution.id)!!.status)
            .isEqualTo(ExecutionStatus.MANUAL_RECONCILIATION)
        assertThat(fixture.gateway.applyCount.getValue(fixture.olt)).isEqualTo(1)
        assertThat(
            fixture.attempts.findByExecutionStepId(fixture.steps.findByExecutionId(execution.id).last().id)
                .count { it.phase == ExecutionPhase.APPLY },
        )
            .isEqualTo(1)
    }

    @Test
    fun `permanent rejection fails immediately while transient failures use bounded exponential retries`() {
        val permanent = Fixture(policy = ExecutionPolicy(maxAttempts = 3, circuitFailureThreshold = 10))
        permanent.gateway.applyFailures[permanent.bras] = ArrayDeque(
            listOf(DeviceOperationException("UNSUPPORTED_CAPABILITY", DeviceFailureKind.PERMANENT)),
        )
        val rejected = permanent.engine.enqueue(permanent.plan, "permanent")
        permanent.engine.run(rejected.id, "worker")
        assertThat(permanent.executions.findById(rejected.id)!!.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(permanent.gateway.applyCount.getValue(permanent.bras)).isEqualTo(1)

        val transient = Fixture(policy = ExecutionPolicy(maxAttempts = 3, circuitFailureThreshold = 10))
        transient.gateway.applyFailures[transient.bras] = ArrayDeque(
            List(3) { DeviceOperationException("TIMEOUT", DeviceFailureKind.TRANSIENT) },
        )
        val exhausted = transient.engine.enqueue(transient.plan, "retry-exhausted")
        transient.engine.run(exhausted.id, "worker")
        assertThat(transient.executions.findById(exhausted.id)!!.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(transient.gateway.applyCount.getValue(transient.bras)).isEqualTo(3)
        assertThat(transient.sleeper.delays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2))
    }

    @Test
    fun `open device circuit prevents dispatch`() {
        val fixture = Fixture(policy = ExecutionPolicy(maxAttempts = 3, circuitFailureThreshold = 2))
        fixture.circuits.save(
            DeviceCircuitBreaker.closed(fixture.tenantId, fixture.bras)
                .recordTransientFailure(fixture.clock.instant(), 2, Duration.ofMinutes(5))
                .recordTransientFailure(fixture.clock.instant(), 2, Duration.ofMinutes(5)),
        )
        val execution = fixture.engine.enqueue(fixture.plan, "circuit-open")

        fixture.engine.run(execution.id, "worker")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(fixture.executions.findById(execution.id)!!.detail).isEqualTo("CIRCUIT_OPEN")
        assertThat(fixture.gateway.applyCount).isEmpty()
    }

    @Test
    fun `completed verified steps compensate in reverse and finish rolled back`() {
        val fixture = Fixture()
        fixture.gateway.applyFailures[fixture.olt] = ArrayDeque(
            listOf(DeviceOperationException("REJECTED", DeviceFailureKind.PERMANENT)),
        )
        val execution = fixture.engine.enqueue(fixture.plan, "rollback")

        fixture.engine.run(execution.id, "worker")

        assertThat(fixture.executions.findById(execution.id)!!.status).isEqualTo(ExecutionStatus.ROLLED_BACK)
        assertThat(fixture.gateway.compensated).containsExactly(fixture.bras)
        assertThat(fixture.steps.findByExecutionId(execution.id).first().status).isEqualTo(ExecutionStepStatus.COMPENSATED)
    }

    @Test
    fun `rollback refuses external drift and requires manual reconciliation`() {
        val fixture = Fixture()
        fixture.gateway.applyFailures[fixture.olt] = ArrayDeque(
            listOf(DeviceOperationException("REJECTED", DeviceFailureKind.PERMANENT)),
        )
        fixture.gateway.driftBeforeCompensation += fixture.bras
        val execution = fixture.engine.enqueue(fixture.plan, "rollback-conflict")

        fixture.engine.run(execution.id, "worker")

        assertThat(fixture.executions.findById(execution.id)!!.status)
            .isEqualTo(ExecutionStatus.MANUAL_RECONCILIATION)
        assertThat(fixture.executions.findById(execution.id)!!.detail).isEqualTo("ROLLBACK_CONFLICT")
        assertThat(fixture.gateway.compensated).isEmpty()
    }

    private class Fixture(val policy: ExecutionPolicy = ExecutionPolicy()) {
        val tenantId: UUID = UuidV7.generate()
        val intentId: UUID = UuidV7.generate()
        val bras = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val olt = DeviceReference(DeviceKind.OLT, UuidV7.generate())
        val clock = MutableClock(Instant.parse("2026-01-02T03:04:05Z"))
        val sleeper = AdvancingSleeper(clock)
        val plans = MemoryPlanRepository()
        val executions = MemoryExecutionRepository()
        val leases = MemoryLeaseRepository()
        val fencedWrites = MemoryFencedExecutionRepository(leases)
        val steps = MemoryStepRepository()
        val attempts = MemoryAttemptRepository()
        val snapshots = MemorySnapshotRepository()
        val circuits = MemoryCircuitRepository()
        val initialStates = mapOf(
            bras to NormalizedDeviceState.of(NormalizedField.CONFIGURED to NormalizedValue.flag(false)),
            olt to NormalizedDeviceState.of(NormalizedField.CONFIGURED to NormalizedValue.flag(false)),
        )
        val desiredStates = mapOf(
            bras to NormalizedDeviceState.of(
                NormalizedField.CONFIGURED to NormalizedValue.flag(true),
                NormalizedField.VLAN_ID to NormalizedValue.number(320),
            ),
            olt to NormalizedDeviceState.of(
                NormalizedField.CONFIGURED to NormalizedValue.flag(true),
                NormalizedField.VLAN_ID to NormalizedValue.number(320),
            ),
        )
        val plan: ProvisionPlan = ProvisionPlan.generate(
            tenantId,
            intentId,
            1,
            listOf(
                step(1, bras, ProvisionOperation.ENSURE_PPPOE_TERMINATION),
                step(2, olt, ProvisionOperation.ENSURE_ACCESS_PORT),
            ),
        ).also { it.validate(); plans.save(it) }
        val gateway = FakeGateway(initialStates, desiredStates)
        val safety = MutableSafetyGate()
        val engine = engine()

        fun engine() = ProvisioningExecutionEngine(
            plans,
            executions,
            leases,
            fencedWrites,
            steps,
            attempts,
            snapshots,
            circuits,
            gateway,
            safety,
            clock,
            sleeper,
            policy,
        )

        private fun step(order: Int, device: DeviceReference, operation: ProvisionOperation) = ProvisionStep.create(
            order,
            device,
            operation,
            mapOf(
                "vlanId" to "320",
                ProvisionStep.PRECONDITION_HASH_ATTRIBUTE to NormalizedStateHash.sha256(initialStates.getValue(device)),
            ),
        )
    }

    private class MutableSafetyGate : ProvisioningSafetyGate {
        var decision = PolicyDecision(true, PolicyCode.AUTO_APPLY_ALLOWED)
        var rejectAtEvaluation: Int? = null
        private var evaluations = 0

        override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode): PolicyDecision {
            evaluations += 1
            return if (evaluations == rejectAtEvaluation) {
                PolicyDecision(false, PolicyCode.STALE_CAPABILITY_EVIDENCE)
            } else {
                decision
            }
        }

        override fun evaluate(plan: ProvisionPlan, mode: ExecutionMode, scope: SafetyGateScope): PolicyDecision =
            if (scope == SafetyGateScope.ROLLBACK) {
                PolicyDecision(true, PolicyCode.ROLLBACK_ALLOWED)
            } else {
                evaluate(plan, mode)
            }
    }

    private class FakeGateway(
        initial: Map<DeviceReference, NormalizedDeviceState>,
        private val desired: Map<DeviceReference, NormalizedDeviceState>,
    ) : ProvisioningDeviceGateway {
        private val current = initial.toMutableMap()
        val applyCount = mutableMapOf<DeviceReference, Int>()
        val applyFailures = mutableMapOf<DeviceReference, ArrayDeque<DeviceOperationException>>()
        val crashAfterApplyFor = mutableSetOf<DeviceReference>()
        val crashAfterCompensateFor = mutableSetOf<DeviceReference>()
        val verificationMismatchFor = mutableSetOf<DeviceReference>()
        val driftBeforeCompensation = mutableSetOf<DeviceReference>()
        val compensated = mutableListOf<DeviceReference>()
        val compensateFailures = mutableMapOf<DeviceReference, ArrayDeque<DeviceOperationException>>()
        var afterApply: ((DispatchableProvisioningWork) -> Unit)? = null

        override fun observe(work: DispatchableProvisioningWork): DeviceStateObservation {
            if (work.phase == ExecutionPhase.ROLLBACK_CHECK && work.device in driftBeforeCompensation) {
                current[work.device] = NormalizedDeviceState.of(NormalizedField.EXTERNAL to NormalizedValue.flag(true))
                driftBeforeCompensation -= work.device
            }
            val state = current.getValue(work.device)
            val matches = state == desired.getValue(work.device) &&
                !(work.phase == ExecutionPhase.VERIFY && work.device in verificationMismatchFor)
            return DeviceStateObservation(NormalizedStateHash.sha256(state), state, matches)
        }

        override fun apply(work: DispatchableProvisioningWork): DeviceApplyResult {
            applyCount[work.device] = applyCount.getOrDefault(work.device, 0) + 1
            applyFailures[work.device]?.removeFirstOrNull()?.let { throw it }
            val state = desired.getValue(work.device)
            current[work.device] = state
            if (crashAfterApplyFor.remove(work.device)) throw SimulatedProcessCrash()
            afterApply?.invoke(work)
            return DeviceApplyResult(NormalizedStateHash.sha256(state), state)
        }

        override fun compensate(work: DispatchableProvisioningWork, before: NormalizedDeviceState): DeviceApplyResult {
            compensateFailures[work.device]?.removeFirstOrNull()?.let { throw it }
            compensated += work.device
            current[work.device] = before
            if (crashAfterCompensateFor.remove(work.device)) throw SimulatedProcessCrash()
            return DeviceApplyResult(NormalizedStateHash.sha256(before), before)
        }

        fun drift(device: DeviceReference) {
            current[device] = NormalizedDeviceState.of(NormalizedField.EXTERNAL to NormalizedValue.flag(true))
        }
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        fun advance(duration: Duration) { current = current.plus(duration) }
    }

    private class AdvancingSleeper(private val clock: MutableClock) : RetrySleeper {
        val delays = mutableListOf<Duration>()
        override fun sleep(duration: Duration) { delays += duration; clock.advance(duration) }
    }

    private class MemoryPlanRepository : ProvisionPlanRepository {
        val values = linkedMapOf<UUID, ProvisionPlan>()
        override fun save(value: ProvisionPlan) = value.also { values[it.id] = it }
        override fun findById(id: UUID) = values[id]
        override fun findLatestByIntentId(intentId: UUID) = values.values.filter { it.intentId == intentId }.maxByOrNull { it.revision }
    }

    private class MemoryExecutionRepository : ProvisionExecutionRepository {
        val values = linkedMapOf<UUID, ProvisionExecution>()
        override fun save(value: ProvisionExecution) = value.also { values[it.id] = it }
        override fun findById(id: UUID) = values[id]
        override fun findByIdempotencyKey(key: String) = values.values.firstOrNull { it.idempotencyKey == key }
    }

    private class MemoryStepRepository : ExecutionStepRepository {
        val values = linkedMapOf<UUID, ExecutionStep>()
        override fun save(value: ExecutionStep) = value.also { values[it.id] = it }
        override fun findByExecutionId(executionId: UUID) = values.values.filter { it.executionId == executionId }.sortedBy { it.order }
    }

    private class MemoryAttemptRepository : StepAttemptRepository {
        val values = linkedMapOf<UUID, StepAttempt>()
        override fun save(value: StepAttempt) = value.also { values[it.id] = it }
        override fun findByExecutionStepId(executionStepId: UUID) =
            values.values.filter { it.executionStepId == executionStepId }.sortedBy { it.attemptNumber }
        override fun findById(id: UUID) = values[id]
        @Synchronized
        override fun completeIfDispatched(id: UUID, status: AttemptStatus, errorCode: String?, completedAt: Instant): Boolean {
            val attempt = values[id] ?: return false
            if (attempt.status != AttemptStatus.DISPATCHED) return false
            attempt.complete(status, errorCode, completedAt)
            return true
        }
    }

    private class MemorySnapshotRepository : StepSnapshotRepository {
        val values = linkedMapOf<UUID, StepSnapshot>()
        override fun save(value: StepSnapshot) = value.also { values[it.id] = it }
        override fun findByExecutionStepId(executionStepId: UUID) = values.values.filter { it.executionStepId == executionStepId }
    }

    private class MemoryCircuitRepository : DeviceCircuitBreakerRepository {
        val values = linkedMapOf<DeviceReference, DeviceCircuitBreaker>()
        override fun save(value: DeviceCircuitBreaker) = value.also { values[it.device] = it }
        override fun findByDevice(device: DeviceReference) = values[device]
    }

    private class MemoryLeaseRepository : DeviceLeaseRepository {
        private val values = mutableMapOf<DeviceReference, DeviceLease>()
        override fun acquire(
            tenantId: UUID,
            device: DeviceReference,
            executionId: UUID,
            ownerId: String,
            now: Instant,
            duration: Duration,
        ): DeviceLease? {
            val current = values[device]
            if (current != null && current.expiresAt.isAfter(now) &&
                (current.executionId != executionId || current.ownerId != ownerId)
            ) return null
            val token = if (current == null) 1 else if (current.executionId == executionId && current.ownerId == ownerId && current.expiresAt.isAfter(now)) {
                current.fencingToken
            } else {
                current.fencingToken + 1
            }
            return DeviceLease(UuidV7.generate(), tenantId, device, executionId, ownerId, token, now.plus(duration))
                .also { values[device] = it }
        }

        override fun release(
            device: DeviceReference,
            executionId: UUID,
            ownerId: String,
            fencingToken: Long,
            now: Instant,
        ): Boolean {
            val current = values[device] ?: return false
            if (current.executionId != executionId || current.ownerId != ownerId || current.fencingToken != fencingToken) return false
            values[device] = current.copy(expiresAt = now)
            return true
        }

        override fun validateAndRenew(
            tenantId: UUID,
            device: DeviceReference,
            executionId: UUID,
            ownerId: String,
            fencingToken: Long,
            now: Instant,
            duration: Duration,
        ): DeviceLease? {
            val current = values[device] ?: return null
            if (current.tenantId != tenantId || current.executionId != executionId || current.ownerId != ownerId ||
                current.fencingToken != fencingToken || !current.expiresAt.isAfter(now)
            ) return null
            return current.copy(expiresAt = now.plus(duration)).also { values[device] = it }
        }
    }

    private class MemoryFencedExecutionRepository(
        private val leases: DeviceLeaseRepository,
    ) : FencedExecutionRepository {
        var beforeCommit: (() -> Unit)? = null

        override fun commitIfLeaseValid(
            tenantId: UUID,
            device: DeviceReference,
            executionId: UUID,
            ownerId: String,
            fencingToken: Long,
            now: Instant,
            duration: Duration,
            write: () -> Boolean,
        ): Boolean {
            beforeCommit?.also {
                beforeCommit = null
                it()
            }
            return leases.validateAndRenew(
                tenantId,
                device,
                executionId,
                ownerId,
                fencingToken,
                now,
                duration,
            ) != null && write()
        }
    }
}
