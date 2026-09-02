package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.DeviceCircuitBreakerRepository
import com.duluin.ftth.provisioning.application.port.outbound.DeviceLeaseRepository
import com.duluin.ftth.provisioning.application.port.outbound.ExecutionStepRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepAttemptRepository
import com.duluin.ftth.provisioning.application.port.outbound.StepSnapshotRepository
import com.duluin.ftth.provisioning.domain.model.AttemptStatus
import com.duluin.ftth.provisioning.domain.model.DeviceCircuitBreaker
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.ExecutionPhase
import com.duluin.ftth.provisioning.domain.model.ExecutionStep
import com.duluin.ftth.provisioning.domain.model.NormalizedDeviceState
import com.duluin.ftth.provisioning.domain.model.NormalizedField
import com.duluin.ftth.provisioning.domain.model.NormalizedValue
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.StepAttempt
import com.duluin.ftth.provisioning.domain.model.StepSnapshot
import com.duluin.ftth.provisioning.domain.model.StepSnapshotKind
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningExecutionPersistenceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var executions: ProvisionExecutionRepository
    @Autowired private lateinit var leases: DeviceLeaseRepository
    @Autowired private lateinit var executionSteps: ExecutionStepRepository
    @Autowired private lateinit var attempts: StepAttemptRepository
    @Autowired private lateinit var snapshots: StepSnapshotRepository
    @Autowired private lateinit var circuits: DeviceCircuitBreakerRepository
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `database allows only one active execution per intent`() {
        val fixture = fixture("execution-single")
        asTenant(fixture.tenantId) {
            executions.save(ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "first"))
        }

        assertThatThrownBy {
            asTenant(fixture.tenantId) {
                executions.save(ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.secondPlanId, "second"))
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `lease takeover requires expiry and advances fencing token`() {
        val fixture = fixture("execution-lease")
        val device = DeviceReference(DeviceKind.ROUTER, UuidV7.generate())
        val now = Instant.parse("2026-01-02T03:04:05Z")
        val first = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "lease-first")
        val second = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.secondPlanId, "lease-second")
        asTenant(fixture.tenantId) { executions.save(first) }
        first.fail("LEASE_TEST_COMPLETE")
        asTenant(fixture.tenantId) { executions.save(first) }
        asTenant(fixture.tenantId) { executions.save(second) }

        val firstLease = asTenant(fixture.tenantId) {
            leases.acquire(fixture.tenantId, device, first.id, "worker-a", now, Duration.ofSeconds(30))
        }
        var blocked = firstLease
        asTenant(fixture.tenantId) {
            blocked = leases.acquire(
                fixture.tenantId,
                device,
                second.id,
                "worker-b",
                now.plusSeconds(29),
                Duration.ofSeconds(30),
            )
        }
        val takeover = asTenant(fixture.tenantId) {
            leases.acquire(fixture.tenantId, device, second.id, "worker-b", now.plusSeconds(30), Duration.ofSeconds(30))
        }

        assertThat(firstLease).isNotNull
        assertThat(firstLease!!.fencingToken).isEqualTo(1)
        assertThat(blocked).isNull()
        assertThat(takeover!!.fencingToken).isGreaterThan(firstLease.fencingToken)
        assertThat(takeover.executionId).isEqualTo(second.id)
        assertThat(
            asTenant(fixture.tenantId) {
                leases.validateAndRenew(
                    fixture.tenantId,
                    device,
                    second.id,
                    "worker-b",
                    takeover.fencingToken,
                    now.plusSeconds(31),
                    Duration.ofSeconds(30),
                )
            },
        ).isNotNull
        assertThat(
            asTenant(fixture.tenantId) {
                listOf(
                    leases.validateAndRenew(
                        fixture.tenantId,
                        device,
                        first.id,
                        "worker-a",
                        firstLease.fencingToken,
                        now.plusSeconds(31),
                        Duration.ofSeconds(30),
                    ),
                )
            }.single(),
        ).isNull()
    }

    @Test
    fun `concurrent duplicate acknowledgements complete an attempt exactly once`() {
        val fixture = fixture("execution-atomic-ack")
        val execution = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "atomic-ack")
        val attempt = asTenant(fixture.tenantId) {
            executions.save(execution)
            val step = executionSteps.save(
                ExecutionStep.pending(
                    fixture.tenantId,
                    execution.id,
                    fixture.firstStepId,
                    1,
                    DeviceReference(DeviceKind.BRAS, UuidV7.generate()),
                ),
            )
            attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    1,
                    "${execution.id}:${fixture.firstStepId}:APPLY",
                    1,
                    Instant.parse("2026-01-02T03:05:05Z"),
                ),
            )
        }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                pool.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    TenantContext.runAs(fixture.tenantId) {
                        attempts.completeIfDispatched(
                            attempt.id,
                            AttemptStatus.SUCCEEDED,
                            null,
                            Instant.parse("2026-01-02T03:04:35Z"),
                        )
                    }
                }
            }
            ready.await()
            start.countDown()

            assertThat(futures.count { it.get() }).isEqualTo(1)
            assertThat(asTenant(fixture.tenantId) { attempts.findById(attempt.id)!!.status })
                .isEqualTo(AttemptStatus.SUCCEEDED)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `step attempts snapshots and circuit state survive reload`() {
        val fixture = fixture("execution-artifacts")
        val execution = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "artifacts")
        val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val deadline = Instant.parse("2026-01-02T03:05:05Z")

        asTenant(fixture.tenantId) {
            executions.save(execution)
            val step = executionSteps.save(ExecutionStep.pending(fixture.tenantId, execution.id, fixture.firstStepId, 1, device))
            attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    1,
                    "${execution.id}:${fixture.firstStepId}:APPLY",
                    7,
                    deadline,
                ).complete(AttemptStatus.SUCCEEDED, null, deadline.minusSeconds(30)),
            )
            snapshots.save(
                StepSnapshot.capture(
                    fixture.tenantId,
                    step.id,
                    StepSnapshotKind.BEFORE,
                    "a".repeat(64),
                    NormalizedDeviceState.of(NormalizedField.VLAN_ID to NormalizedValue.number(320)),
                    deadline.minusSeconds(40),
                ),
            )
            val circuit = DeviceCircuitBreaker.closed(fixture.tenantId, device)
                .recordTransientFailure(deadline.minusSeconds(1), 2, Duration.ofMinutes(1))
                .recordTransientFailure(deadline, 2, Duration.ofMinutes(1))
            circuits.save(circuit)
        }

        asTenant(fixture.tenantId) {
            assertThat(executionSteps.findByExecutionId(execution.id)).hasSize(1)
            assertThat(attempts.findByExecutionStepId(executionSteps.findByExecutionId(execution.id).single().id).single().fencingToken)
                .isEqualTo(7)
            assertThat(snapshots.findByExecutionStepId(executionSteps.findByExecutionId(execution.id).single().id).single().state.values)
                .containsEntry(NormalizedField.VLAN_ID, NormalizedValue.number(320))
            assertThat(circuits.findByDevice(device)!!.openUntil).isEqualTo(deadline.plusSeconds(60))
        }
    }

    private fun fixture(prefix: String): Fixture {
        val tenantId = tenantApi.ensureTenant("$prefix-${UUID.randomUUID().toString().take(8)}", prefix).id
        return asTenant(tenantId) {
            val poolId = UuidV7.generate()
            em.createNativeQuery(
                "INSERT INTO provisioning_vlan_pool (id, tenant_id, name, vlan_start, vlan_end) VALUES (:id, :tenant, :name, 100, 400)",
            ).setParameter("id", poolId).setParameter("tenant", tenantId).setParameter("name", UUID.randomUUID().toString())
                .executeUpdate()
            val profileId = UuidV7.generate()
            em.createNativeQuery(
                "INSERT INTO provisioning_segment_profile (id, tenant_id, name, pool_id) VALUES (:id, :tenant, :name, :pool)",
            ).setParameter("id", profileId).setParameter("tenant", tenantId).setParameter("name", UUID.randomUUID().toString())
                .setParameter("pool", poolId).executeUpdate()
            val intentId = UuidV7.generate()
            em.createNativeQuery(
                """INSERT INTO provisioning_service_intent
                   (id, tenant_id, subscription_id, segment_profile_id, encapsulation, status)
                   VALUES (:id, :tenant, :subscription, :profile, 'SINGLE_TAG', 'ACTIVE')""",
            ).setParameter("id", intentId).setParameter("tenant", tenantId).setParameter("subscription", UuidV7.generate())
                .setParameter("profile", profileId).executeUpdate()
            val firstPlanId = insertPlan(tenantId, intentId, 1)
            val firstStepId = insertStep(tenantId, firstPlanId)
            val secondPlanId = insertPlan(tenantId, intentId, 2)
            insertStep(tenantId, secondPlanId)
            Fixture(tenantId, intentId, firstPlanId, firstStepId, secondPlanId)
        }
    }

    private fun insertPlan(tenantId: UUID, intentId: UUID, revision: Int): UUID = UuidV7.generate().also { planId ->
        em.createNativeQuery(
            """INSERT INTO provisioning_plan (id, tenant_id, intent_id, revision, status, content_hash)
               VALUES (:id, :tenant, :intent, :revision, 'GENERATED', :hash)""",
        ).setParameter("id", planId).setParameter("tenant", tenantId).setParameter("intent", intentId)
            .setParameter("revision", revision).setParameter("hash", revision.toString().repeat(64)).executeUpdate()
    }

    private fun insertStep(tenantId: UUID, planId: UUID): UUID = UuidV7.generate().also { stepId ->
        em.createNativeQuery(
            """INSERT INTO provisioning_step
               (id, tenant_id, plan_id, step_order, device_kind, device_id, operation)
               VALUES (:id, :tenant, :plan, 1, 'BRAS', :device, 'ENSURE_PPPOE_TERMINATION')""",
        ).setParameter("id", stepId).setParameter("tenant", tenantId).setParameter("plan", planId)
            .setParameter("device", UuidV7.generate()).executeUpdate()
    }

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    private data class Fixture(
        val tenantId: UUID,
        val intentId: UUID,
        val firstPlanId: UUID,
        val firstStepId: UUID,
        val secondPlanId: UUID,
    )
}
