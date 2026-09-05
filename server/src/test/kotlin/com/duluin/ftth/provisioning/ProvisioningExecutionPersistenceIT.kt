package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.CollectorHeartbeat
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import com.duluin.ftth.provisioning.adapter.outbound.persistence.StepAttemptJpaRepository
import com.duluin.ftth.monitoring.application.service.CollectorProvisioningExchange
import com.duluin.ftth.monitoring.AlarmsChangedEvent
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.domain.model.Collector
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
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProvisioningExecutionPersistenceIT.HeartbeatCommitFailureConfig::class)
class ProvisioningExecutionPersistenceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var executions: ProvisionExecutionRepository
    @Autowired private lateinit var leases: DeviceLeaseRepository
    @Autowired private lateinit var executionSteps: ExecutionStepRepository
    @Autowired private lateinit var attempts: StepAttemptRepository
    @Autowired private lateinit var snapshots: StepSnapshotRepository
    @Autowired private lateinit var circuits: DeviceCircuitBreakerRepository
    @Autowired private lateinit var collectorExchange: CollectorProvisioningExchange
    @Autowired private lateinit var collectorRepository: CollectorRepository
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var heartbeatCommitFailure: HeartbeatCommitFailure
    @Autowired private lateinit var attemptJpa: StepAttemptJpaRepository
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
        }.isInstanceOf(ConflictException::class.java)
            .hasMessage("ACTIVE_EXECUTION_EXISTS")
    }

    @Test
    fun `concurrent active execution creation returns one stable domain conflict`() {
        val fixture = fixture("execution-concurrent-active")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val outcomes = listOf(fixture.firstPlanId, fixture.secondPlanId).mapIndexed { index, planId ->
                workers.submit<String> {
                    ready.countDown()
                    start.await()
                    try {
                        asTenant(fixture.tenantId) {
                            executions.save(
                                ProvisionExecution.queue(fixture.tenantId, fixture.intentId, planId, "active-$index"),
                            )
                        }
                        "CREATED"
                    } catch (error: ConflictException) {
                        error.message.orEmpty()
                    }
                }
            }
            ready.await()
            start.countDown()
            assertThat(outcomes.map { it.get() }).containsExactlyInAnyOrder("CREATED", "ACTIVE_EXECUTION_EXISTS")
        } finally {
            workers.shutdownNow()
        }
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
        val sameOwnerReentry = asTenant(fixture.tenantId) {
            listOf(
                leases.acquire(
                    fixture.tenantId, device, first.id, "worker-a", now.plusSeconds(1), Duration.ofSeconds(30),
                ),
            )
        }.single()
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
        assertThat(sameOwnerReentry).isNull()
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
    fun `concurrent first lease acquisition returns one owner and one blocked result`() {
        val fixture = fixture("execution-first-lease-race")
        val device = DeviceReference(DeviceKind.ROUTER, UuidV7.generate())
        val now = Instant.parse("2026-09-02T12:00:00Z")
        val first = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "first-race")
        val second = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.secondPlanId, "second-race")
        asTenant(fixture.tenantId) { executions.save(first) }
        first.fail("FIRST_READY")
        asTenant(fixture.tenantId) { executions.save(first); executions.save(second) }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first to "worker-a", second to "worker-b").map { (execution, owner) ->
                workers.submit<com.duluin.ftth.provisioning.domain.model.DeviceLease?> {
                    ready.countDown()
                    start.await()
                    asTenant(fixture.tenantId) {
                        listOf(leases.acquire(fixture.tenantId, device, execution.id, owner, now, Duration.ofSeconds(30)))
                    }.single()
                }
            }
            ready.await()
            start.countDown()

            assertThat(results.map { it.get() }.count { it != null }).isEqualTo(1)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `stale acknowledgement cannot complete after lease fencing token advances`() {
        val fixture = fixture("execution-stale-ack")
        val device = DeviceReference(DeviceKind.ROUTER, UuidV7.generate())
        val now = Instant.parse("2026-09-02T12:00:00Z")
        val first = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "stale-ack-first")
        val second = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.secondPlanId, "stale-ack-second")
        val attempt = asTenant(fixture.tenantId) {
            executions.save(first)
            val lease = leases.acquire(fixture.tenantId, device, first.id, "worker-a", now, Duration.ofSeconds(30))!!
            val step = executionSteps.save(
                ExecutionStep.pending(fixture.tenantId, first.id, fixture.firstStepId, 1, device),
            )
            attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId, step.id, ExecutionPhase.APPLY, 1, "stale-ack-attempt",
                    lease.fencingToken, now.plusSeconds(20), now,
                ),
            )
        }
        first.fail("FIRST_COMPLETE")
        asTenant(fixture.tenantId) { executions.save(first); executions.save(second) }
        asTenant(fixture.tenantId) {
            leases.acquire(fixture.tenantId, device, second.id, "worker-b", now.plusSeconds(31), Duration.ofSeconds(30))
        }

        val accepted = asTenant(fixture.tenantId) {
            attempts.completeAcknowledgementIfCurrentLease(
                attempt.id, AttemptStatus.SUCCEEDED, null, now.plusSeconds(10), now.plusSeconds(31),
            )
        }

        assertThat(accepted).isFalse()
        assertThat(asTenant(fixture.tenantId) { attempts.findById(attempt.id)!!.status })
            .isEqualTo(AttemptStatus.DISPATCHED)
    }

    @Test
    fun `collector channel emits persisted attempt and acknowledges result and report durably`() {
        val fixture = fixture("collector-channel")
        val execution = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "collector-channel")
        val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val collectorId = UuidV7.generate()
        val deadline = Instant.now().plusSeconds(300)
        val attempt = asTenant(fixture.tenantId) {
            em.createNativeQuery(
                """INSERT INTO collector
                   (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
                   VALUES (:id, :tenant, :name, :hash, 'test', 'ACTIVE', 60)""",
            ).setParameter("id", collectorId).setParameter("tenant", fixture.tenantId)
                .setParameter("name", "collector-${UUID.randomUUID()}")
                .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .executeUpdate()
            executions.save(execution)
            val lease = leases.acquire(
                fixture.tenantId, device, execution.id, "collector-worker", deadline.minusSeconds(30), Duration.ofMinutes(5),
            )!!
            val step = executionSteps.save(
                ExecutionStep.pending(fixture.tenantId, execution.id, fixture.firstStepId, 1, device),
            )
            attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    1,
                    "collector-channel-attempt",
                    lease.fencingToken,
                    deadline,
                    deadline.minusSeconds(30),
                ),
            )
        }
        val result = ProvisioningStepResult(
            planId = fixture.firstPlanId.toString(),
            revision = 1,
            stepId = fixture.firstStepId.toString(),
            attemptId = attempt.id.toString(),
            targetId = device.id.toString(),
            operationClass = "ENSURE_PPPOE_TERMINATION",
            idempotencyKey = attempt.idempotencyKey,
            fencingEpoch = attempt.fencingToken,
            success = false,
            completedAt = deadline.minusSeconds(10),
            errorCode = ProvisioningErrorCode.STALE_PRECONDITION,
        )
        val report = DeviceCapabilityReport(
            targetId = device.id.toString(),
            fingerprint = DeviceFingerprint("MIKROTIK", "CCR", "7.20", "HTTPS_REST"),
            capabilities = setOf("SINGLE_TAG_802_1Q"),
            reportedAt = Instant.now().minusSeconds(20),
            operationClasses = setOf("ENSURE_PPPOE_TERMINATION"),
        )
        val unownedReport = report.copy(targetId = UuidV7.generate().toString())

        val target = ProvisioningTarget(device.id.toString(), device.kind.name, "router.invalid", "HTTPS_REST")
        val pending = asTenant(fixture.tenantId) {
            collectorExchange.exchange(collectorId, fixture.tenantId, CollectorHeartbeat("test"), listOf(target))
        }
        val otherCollectorId = UuidV7.generate()
        asTenant(fixture.tenantId) {
            em.createNativeQuery(
                """INSERT INTO collector
                   (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
                   VALUES (:id, :tenant, :name, :hash, 'other', 'ACTIVE', 60)""",
            ).setParameter("id", otherCollectorId).setParameter("tenant", fixture.tenantId)
                .setParameter("name", "collector-${UUID.randomUUID()}")
                .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .executeUpdate()
        }
        val otherCollectorCommands = asTenant(fixture.tenantId) {
            collectorExchange.exchange(otherCollectorId, fixture.tenantId, CollectorHeartbeat("test"), listOf(target)).commands
        }
        val wrongCollectorAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                otherCollectorId,
                fixture.tenantId,
                CollectorHeartbeat("test", provisioningResults = listOf(result)),
                listOf(target),
            ).acknowledgement
        }
        val wrongFenceAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat("test", provisioningResults = listOf(result.copy(fencingEpoch = 6))),
                listOf(target),
            ).acknowledgement
        }
        val wrongPhaseAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat(
                    "test",
                    provisioningResults = listOf(result.copy(phase = com.duluin.ftth.contract.ProvisioningCommandPhase.VERIFY)),
                ),
                listOf(target),
            ).acknowledgement
        }
        val legacyAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat(
                    "test",
                    provisioningResults = listOf(result.copy(attemptId = null, targetId = null, fencingEpoch = 0)),
                ),
                listOf(target),
            ).acknowledgement
        }
        val acknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat("test", deviceReports = listOf(report, unownedReport), provisioningResults = listOf(result)),
                listOf(target),
            ).acknowledgement
        }
        val duplicateAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat("test", deviceReports = listOf(report), provisioningResults = listOf(result)),
                listOf(target),
            ).acknowledgement
        }
        val staleAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat("test", provisioningResults = listOf(result.copy(idempotencyKey = "stale-result"))),
                listOf(target),
            ).acknowledgement
        }
        val retryAttempt = asTenant(fixture.tenantId) {
            attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    attempt.executionStepId,
                    ExecutionPhase.APPLY,
                    2,
                    attempt.idempotencyKey,
                    attempt.fencingToken,
                    deadline.plusSeconds(60),
                    deadline,
                ),
            )
        }
        val retryPending = asTenant(fixture.tenantId) {
            collectorExchange.exchange(collectorId, fixture.tenantId, CollectorHeartbeat("test"), listOf(target))
        }
        val retryAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat(
                    "test",
                    provisioningResults = listOf(
                        result.copy(
                            attemptId = retryAttempt.id.toString(),
                            fencingEpoch = retryAttempt.fencingToken,
                            completedAt = deadline.plusSeconds(10),
                        ),
                    ),
                ),
                listOf(target),
            ).acknowledgement
        }
        val lateAttempt = asTenant(fixture.tenantId) {
            attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    attempt.executionStepId,
                    ExecutionPhase.APPLY,
                    3,
                    attempt.idempotencyKey,
                    attempt.fencingToken,
                    deadline.plusSeconds(120),
                    deadline.plusSeconds(60),
                ),
            )
        }
        asTenant(fixture.tenantId) {
            collectorExchange.exchange(collectorId, fixture.tenantId, CollectorHeartbeat("test"), listOf(target))
        }
        val lateHash = "c".repeat(64)
        val lateAcknowledgement = asTenant(fixture.tenantId) {
            collectorExchange.exchange(
                collectorId,
                fixture.tenantId,
                CollectorHeartbeat(
                    "test",
                    provisioningResults = listOf(
                        ProvisioningStepResult(
                            planId = fixture.firstPlanId.toString(),
                            revision = 1,
                            stepId = fixture.firstStepId.toString(),
                            attemptId = lateAttempt.id.toString(),
                            targetId = device.id.toString(),
                            operationClass = "ENSURE_PPPOE_TERMINATION",
                            idempotencyKey = lateAttempt.idempotencyKey,
                            fencingEpoch = lateAttempt.fencingToken,
                            success = true,
                            completedAt = lateAttempt.deadline.plusMillis(1),
                            apply = ProvisioningApplyResult(lateAttempt.deadline.minusSeconds(1), true, lateHash),
                            verification = ProvisioningVerificationObservation(
                                lateAttempt.deadline,
                                true,
                                lateHash,
                            ),
                        ),
                    ),
                ),
                listOf(target),
            ).acknowledgement
        }

        assertThat(pending.commands.map { it.idempotencyKey }).contains(attempt.idempotencyKey)
        assertThat(pending.commands.single().planId).isEqualTo(fixture.firstPlanId.toString())
        assertThat(pending.commands.single().revision).isEqualTo(1)
        assertThat(pending.commands.single().stepId).isEqualTo(fixture.firstStepId.toString())
        assertThat(pending.commands.single().fencingEpoch).isEqualTo(attempt.fencingToken)
        assertThat(pending.commands.single().attemptId).isEqualTo(attempt.id.toString())
        assertThat(otherCollectorCommands).isEmpty()
        assertThat(wrongCollectorAcknowledgement).isEqualTo(com.duluin.ftth.contract.ProvisioningAcknowledgement())
        assertThat(wrongFenceAcknowledgement).isEqualTo(com.duluin.ftth.contract.ProvisioningAcknowledgement())
        assertThat(wrongPhaseAcknowledgement).isEqualTo(com.duluin.ftth.contract.ProvisioningAcknowledgement())
        assertThat(legacyAcknowledgement).isEqualTo(com.duluin.ftth.contract.ProvisioningAcknowledgement())
        assertThat(acknowledgement.resultAttemptIds).containsExactly(attempt.id.toString())
        assertThat(
            asTenant(fixture.tenantId) {
                collectorExchange.exchange(collectorId, fixture.tenantId, CollectorHeartbeat("test"), listOf(target)).commands
            },
        ).isEmpty()
        assertThat(acknowledgement.deviceReportKeys).containsExactly("${report.targetId}@${report.reportedAt}")
        assertThat(duplicateAcknowledgement).isEqualTo(acknowledgement)
        assertThat(staleAcknowledgement).isEqualTo(com.duluin.ftth.contract.ProvisioningAcknowledgement())
        assertThat(retryPending.commands.single().attemptId).isEqualTo(retryAttempt.id.toString())
        assertThat(retryAcknowledgement.resultAttemptIds).containsExactly(retryAttempt.id.toString())
        assertThat(lateAcknowledgement.resultAttemptIds).containsExactly(lateAttempt.id.toString())
        assertThat(asTenant(fixture.tenantId) { attempts.findById(lateAttempt.id)!!.status })
            .isEqualTo(AttemptStatus.TRANSIENT_FAILURE)
        assertThat(asTenant(fixture.tenantId) { attempts.findById(lateAttempt.id)!!.errorCode })
            .isEqualTo("DEADLINE_EXCEEDED")
        assertThat(asTenant(fixture.tenantId) { attempts.findById(attempt.id)!!.status })
            .isEqualTo(AttemptStatus.PERMANENT_FAILURE)
        assertThat(asTenant(fixture.tenantId) {
            (em.createNativeQuery("SELECT count(*) FROM provisioning_collector_device_report WHERE target_id = :target")
                .setParameter("target", report.targetId).singleResult as Number).toLong()
        }).isEqualTo(1)
        assertThat(asTenant(fixture.tenantId) {
            (em.createNativeQuery("SELECT count(*) FROM provisioning_collector_device_report WHERE target_id = :target")
                .setParameter("target", unownedReport.targetId).singleResult as Number).toLong()
        }).isZero()
        assertThat(asTenant(fixture.tenantId) {
            (em.createNativeQuery("SELECT count(*) FROM provisioning_collector_result_receipt WHERE idempotency_key = :key")
                .setParameter("key", result.idempotencyKey).singleResult as Number).toLong()
        }).isEqualTo(3)
        val otherTenantId = tenantApi.ensureTenant("collector-channel-other-${UUID.randomUUID()}", "collector-channel-other").id
        assertThat(asTenant(otherTenantId) {
            (em.createNativeQuery("SELECT count(*) FROM provisioning_collector_result_receipt WHERE idempotency_key = :key")
                .setParameter("key", result.idempotencyKey).singleResult as Number).toLong()
        }).isZero()
    }

    @Test
    fun `collector claim atomically rejects terminal attempt and wrong device`() {
        val fixture = fixture("collector-claim-guard")
        val execution = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "collector-claim-guard")
        val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val collectorId = UuidV7.generate()
        val now = Instant.parse("2026-09-02T12:00:00Z")
        val (terminal, dispatched) = asTenant(fixture.tenantId) {
            em.createNativeQuery(
                """INSERT INTO collector
                   (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
                   VALUES (:id, :tenant, :name, :hash, 'claim', 'ACTIVE', 60)""",
            ).setParameter("id", collectorId).setParameter("tenant", fixture.tenantId)
                .setParameter("name", "collector-${UUID.randomUUID()}")
                .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .executeUpdate()
            executions.save(execution)
            val step = executionSteps.save(
                ExecutionStep.pending(fixture.tenantId, execution.id, fixture.firstStepId, 1, device),
            )
            val terminalAttempt = attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    1,
                    "terminal-claim",
                    1,
                    now.plusSeconds(30),
                    now,
                ).complete(AttemptStatus.SUCCEEDED, null, now.plusSeconds(1)),
            )
            val dispatchedAttempt = attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    2,
                    "eligible-claim",
                    2,
                    now.plusSeconds(60),
                    now.plusSeconds(2),
                ),
            )
            terminalAttempt to dispatchedAttempt
        }

        val terminalClaimed = asTenant(fixture.tenantId) {
            attemptJpa.claimCollector(terminal.id, collectorId, device.id)
        }
        val wrongDeviceClaimed = asTenant(fixture.tenantId) {
            attemptJpa.claimCollector(dispatched.id, collectorId, UuidV7.generate())
        }
        val eligibleClaimed = asTenant(fixture.tenantId) {
            attemptJpa.claimCollector(dispatched.id, collectorId, device.id)
        }

        assertThat(terminalClaimed).isZero()
        assertThat(wrongDeviceClaimed).isZero()
        assertThat(eligibleClaimed).isEqualTo(1)
        assertThat(asTenant(fixture.tenantId) {
            (em.createNativeQuery(
                "SELECT count(*) FROM provisioning_step_attempt WHERE id = :id AND collector_id IS NOT NULL",
            ).setParameter("id", terminal.id).singleResult as Number).toLong()
        }).isZero()
    }

    @Test
    fun `v121 rejects cross tenant attempt receipt and invalid state hash`() {
        val owner = fixture("collector-receipt-owner")
        val otherTenantId = tenantApi.ensureTenant("collector-receipt-other-${UUID.randomUUID()}", "collector-receipt-other").id
        val collectorId = UuidV7.generate()
        val execution = ProvisionExecution.queue(owner.tenantId, owner.intentId, owner.firstPlanId, "collector-receipt-owner")
        val attempt = asTenant(owner.tenantId) {
            executions.save(execution)
            val step = executionSteps.save(
                ExecutionStep.pending(
                    owner.tenantId,
                    execution.id,
                    owner.firstStepId,
                    1,
                    DeviceReference(DeviceKind.BRAS, UuidV7.generate()),
                ),
            )
            attempts.save(
                StepAttempt.dispatch(
                    owner.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    1,
                    "receipt-fk-attempt",
                    1,
                    Instant.parse("2026-09-02T12:05:00Z"),
                ),
            )
        }
        asTenant(otherTenantId) {
            em.createNativeQuery(
                """INSERT INTO collector
                   (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
                   VALUES (:id, :tenant, :name, :hash, 'fk', 'ACTIVE', 60)""",
            ).setParameter("id", collectorId).setParameter("tenant", otherTenantId)
                .setParameter("name", "collector-${UUID.randomUUID()}")
                .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .executeUpdate()
        }

        assertThatThrownBy {
            asTenant(otherTenantId) {
                insertResultReceipt(otherTenantId, collectorId, attempt.id, "a".repeat(64))
            }
        }

        val ownerCollectorId = UuidV7.generate()
        asTenant(owner.tenantId) {
            em.createNativeQuery(
                """INSERT INTO collector
                   (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
                   VALUES (:id, :tenant, :name, :hash, 'hash', 'ACTIVE', 60)""",
            ).setParameter("id", ownerCollectorId).setParameter("tenant", owner.tenantId)
                .setParameter("name", "collector-${UUID.randomUUID()}")
                .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .executeUpdate()
        }
        assertThatThrownBy {
            asTenant(owner.tenantId) {
                insertResultReceipt(owner.tenantId, ownerCollectorId, attempt.id, "invalid-hash")
            }
        }
    }

    @Test
    fun `v121 rejects cross tenant collector links for attempts reports and receipts`() {
        val owner = fixture("collector-fk-owner")
        val otherTenantId = tenantApi.ensureTenant("collector-fk-other-${UUID.randomUUID()}", "collector-fk-other").id
        val foreignCollectorId = UuidV7.generate()
        val execution = ProvisionExecution.queue(owner.tenantId, owner.intentId, owner.firstPlanId, "collector-fk-owner")
        val deviceId = UuidV7.generate()
        val attempt = asTenant(owner.tenantId) {
            executions.save(execution)
            val step = executionSteps.save(
                ExecutionStep.pending(
                    owner.tenantId,
                    execution.id,
                    owner.firstStepId,
                    1,
                    DeviceReference(DeviceKind.BRAS, deviceId),
                ),
            )
            attempts.save(
                StepAttempt.dispatch(
                    owner.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    1,
                    "collector-cross-tenant",
                    1,
                    Instant.parse("2026-09-02T12:05:00Z"),
                ),
            )
        }
        asTenant(otherTenantId) {
            em.createNativeQuery(
                """INSERT INTO collector
                   (id, tenant_id, name, api_key_hash, api_key_hint, status, poll_interval_seconds)
                   VALUES (:id, :tenant, :name, :hash, 'cross', 'ACTIVE', 60)""",
            ).setParameter("id", foreignCollectorId).setParameter("tenant", otherTenantId)
                .setParameter("name", "collector-${UUID.randomUUID()}")
                .setParameter("hash", UUID.randomUUID().toString().replace("-", "").repeat(2))
                .executeUpdate()
        }

        assertThatThrownBy {
            asTenant(owner.tenantId) {
                em.createNativeQuery(
                    "UPDATE provisioning_step_attempt SET collector_id = :collector WHERE id = :attempt",
                ).setParameter("collector", foreignCollectorId).setParameter("attempt", attempt.id).executeUpdate()
            }
        }
        assertThatThrownBy {
            asTenant(owner.tenantId) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_collector_device_report
                       (id, tenant_id, collector_id, report_key, target_id, vendor, model, firmware, transport, capabilities, reported_at)
                       VALUES (:id, :tenant, :collector, 'cross-report', :target, 'MIKROTIK', 'CCR', '7.20', 'HTTPS_REST', '', now())""",
                ).setParameter("id", UuidV7.generate()).setParameter("tenant", owner.tenantId)
                    .setParameter("collector", foreignCollectorId).setParameter("target", deviceId.toString()).executeUpdate()
            }
        }
        assertThatThrownBy {
            asTenant(owner.tenantId) {
                insertResultReceipt(owner.tenantId, foreignCollectorId, attempt.id, "a".repeat(64))
            }
        }
    }

    @Test
    fun `heartbeat does not expose ACK when before commit persistence fails`() {
        val fixture = fixture("collector-commit-boundary")
        val apiKey = "ftthc_commit_boundary_${UUID.randomUUID()}"
        val collector = asTenant(fixture.tenantId) {
            val created = Collector.create(fixture.tenantId, "collector-commit-boundary", 60) { apiKey }
            collectorRepository.save(created.collector)
        }
        val execution = ProvisionExecution.queue(fixture.tenantId, fixture.intentId, fixture.firstPlanId, "commit-boundary")
        val device = DeviceReference(DeviceKind.BRAS, UuidV7.generate())
        val deadline = Instant.parse("2026-09-02T12:05:00Z")
        val attempt = asTenant(fixture.tenantId) {
            executions.save(execution)
            val step = executionSteps.save(
                ExecutionStep.pending(fixture.tenantId, execution.id, fixture.firstStepId, 1, device),
            )
            attempts.save(
                StepAttempt.dispatch(
                    fixture.tenantId,
                    step.id,
                    ExecutionPhase.APPLY,
                    1,
                    "commit-boundary-attempt",
                    1,
                    deadline,
                    deadline.minusSeconds(30),
                ),
            )
        }
        asTenant(fixture.tenantId) {
            assertThat(attemptJpa.claimCollector(attempt.id, collector.id, device.id)).isEqualTo(1)
        }
        val result = ProvisioningStepResult(
            planId = fixture.firstPlanId.toString(),
            revision = 1,
            stepId = fixture.firstStepId.toString(),
            attemptId = attempt.id.toString(),
            targetId = device.id.toString(),
            operationClass = "ENSURE_PPPOE_TERMINATION",
            idempotencyKey = attempt.idempotencyKey,
            fencingEpoch = attempt.fencingToken,
            success = false,
            completedAt = deadline.minusSeconds(10),
            errorCode = ProvisioningErrorCode.STALE_PRECONDITION,
        )
        heartbeatCommitFailure.arm()

        assertThatThrownBy {
            mockMvc.perform(
                post("/api/collector/heartbeat")
                    .header(com.duluin.ftth.contract.CollectorProtocol.API_KEY_HEADER, apiKey)
                    .header(com.duluin.ftth.contract.CollectorProtocol.PROTOCOL_VERSION_HEADER, "1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(CollectorHeartbeat("test", provisioningResults = listOf(result)))),
            ).andReturn()
        }.hasRootCauseMessage("forced heartbeat commit failure")
        assertThat(asTenant(fixture.tenantId) { attempts.findById(attempt.id)!!.status }).isEqualTo(AttemptStatus.DISPATCHED)
        assertThat(asTenant(fixture.tenantId) {
            (em.createNativeQuery("SELECT count(*) FROM provisioning_collector_result_receipt WHERE attempt_id = :attempt")
                .setParameter("attempt", attempt.id).singleResult as Number).toLong()
        }).isZero()
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
                   VALUES (:id, :tenant, :subscription, :profile, 'SINGLE_TAG', 'DRAFT')""",
            ).setParameter("id", intentId).setParameter("tenant", tenantId).setParameter("subscription", UuidV7.generate())
                .setParameter("profile", profileId).executeUpdate()
            em.createNativeQuery("UPDATE provisioning_service_intent SET status = 'ACTIVE' WHERE id = :id")
                .setParameter("id", intentId).executeUpdate()
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

    private fun insertResultReceipt(tenantId: UUID, collectorId: UUID, attemptId: UUID, verificationHash: String) {
        em.createNativeQuery(
            """INSERT INTO provisioning_collector_result_receipt
               (id, tenant_id, collector_id, idempotency_key, plan_id, revision, step_id, attempt_id, target_id,
                operation_class, fencing_epoch, phase, success, completed_at, error_code, verification_state_hash)
               VALUES (:id, :tenant, :collector, 'receipt-key', :plan, 1, :step, :attempt, :target,
                'ENSURE_TAGGED_VLAN', 1, 'APPLY', false, now(), 'STALE_PRECONDITION', :hash)""",
        ).setParameter("id", UuidV7.generate())
            .setParameter("tenant", tenantId)
            .setParameter("collector", collectorId)
            .setParameter("plan", UuidV7.generate().toString())
            .setParameter("step", UuidV7.generate().toString())
            .setParameter("attempt", attemptId)
            .setParameter("target", UuidV7.generate().toString())
            .setParameter("hash", verificationHash)
            .executeUpdate()
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

    @TestConfiguration(proxyBeanMethods = false)
    class HeartbeatCommitFailureConfig {
        @Bean
        fun heartbeatCommitFailure() = HeartbeatCommitFailure()
    }

    class HeartbeatCommitFailure {
        private var armed = false

        fun arm() {
            armed = true
        }

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        fun failBeforeCommit(event: AlarmsChangedEvent) {
            if (!armed) return
            armed = false
            throw IllegalStateException("forced heartbeat commit failure")
        }
    }
}
