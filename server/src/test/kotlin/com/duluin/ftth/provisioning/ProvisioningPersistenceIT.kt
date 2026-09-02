package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionExecutionRepository
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningPersistenceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @Autowired private lateinit var provisionPlans: ProvisionPlanRepository
    @Autowired private lateinit var executions: ProvisionExecutionRepository
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `tenant isolation hides allocations and their references`() {
        val tenantA = tenant("provision-a")
        val tenantB = tenant("provision-b")
        val allocationId = UuidV7.generate()
        asTenant(tenantA) {
            insertPoolAndAllocation(tenantA, allocationId)
            em.createNativeQuery(
                """INSERT INTO provisioning_vlan_allocation_reference
                   (id, tenant_id, allocation_id, reference_kind, reference_id)
                   VALUES (:id, :tenant, :allocation, 'SUBSCRIPTION', :reference)""",
            ).setParameter("id", UuidV7.generate())
                .setParameter("tenant", tenantA)
                .setParameter("allocation", allocationId)
                .setParameter("reference", UuidV7.generate())
                .executeUpdate()
        }

        assertThat(count(tenantA, "provisioning_vlan_allocation", allocationId)).isEqualTo(1)
        assertThat(count(tenantB, "provisioning_vlan_allocation", allocationId)).isZero()
        assertThat(count(tenantB, "provisioning_vlan_allocation_reference", allocationId, "allocation_id")).isZero()
    }

    @Test
    fun `database enforces active allocation uniqueness and maintains reference count`() {
        val tenantId = tenant("provision-unique")
        val first = UuidV7.generate()
        asTenant(tenantId) {
            insertPoolAndAllocation(tenantId, first)
            val referenceId = UuidV7.generate()
            em.createNativeQuery(
                """INSERT INTO provisioning_vlan_allocation_reference
                   (id, tenant_id, allocation_id, reference_kind, reference_id)
                   VALUES (:id, :tenant, :allocation, 'SERVICE_INTENT', :reference)""",
            ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                .setParameter("allocation", first).setParameter("reference", referenceId).executeUpdate()
            em.flush()
            assertThat(number("SELECT reference_count FROM provisioning_vlan_allocation WHERE id = :id", first)).isEqualTo(1)

            em.createNativeQuery("DELETE FROM provisioning_vlan_allocation_reference WHERE allocation_id = :id")
                .setParameter("id", first).executeUpdate()
            em.flush()
            assertThat(number("SELECT reference_count FROM provisioning_vlan_allocation WHERE id = :id", first)).isZero()
        }

        assertThatThrownBy {
            asTenant(tenantId) {
                insertAllocation(tenantId, UuidV7.generate(), existingPool(tenantId), existingIntent(tenantId), 110)
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
    }

    @Test
    fun `validated plan payload hash is immutable and execution key is idempotent`() {
        val tenantId = tenant("provision-plan")
        val planId = UuidV7.generate()
        asTenant(tenantId) {
            val poolId = insertPool(tenantId)
            val intentId = insertIntent(tenantId, poolId)
            val stepId = UuidV7.generate()
            em.createNativeQuery(
                """INSERT INTO provisioning_plan
                   (id, tenant_id, intent_id, revision, status, content_hash)
                   VALUES (:id, :tenant, :intent, 1, 'GENERATED', :hash)""",
            ).setParameter("id", planId).setParameter("tenant", tenantId)
                .setParameter("intent", intentId).setParameter("hash", "a".repeat(64)).executeUpdate()
            em.createNativeQuery(
                """INSERT INTO provisioning_step
                   (id, tenant_id, plan_id, step_order, device_kind, device_id, operation)
                   VALUES (:id, :tenant, :plan, 1, 'ROUTER', :device, 'ENSURE_TAGGED_VLAN')""",
            ).setParameter("id", stepId).setParameter("tenant", tenantId)
                 .setParameter("plan", planId).setParameter("device", FIXED_DEVICE).executeUpdate()
            em.createNativeQuery(
                """INSERT INTO provisioning_step_attribute
                   (id, tenant_id, step_id, attribute_key, attribute_value)
                   VALUES (:id, :tenant, :step, 'vlanId', '110')""",
            ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                .setParameter("step", stepId).executeUpdate()
            em.createNativeQuery(
                "UPDATE provisioning_plan SET content_hash = provisioning_calculate_plan_hash(id, tenant_id) WHERE id = :id",
            ).setParameter("id", planId).executeUpdate()
            em.createNativeQuery("UPDATE provisioning_plan SET status = 'VALIDATED' WHERE id = :id")
                .setParameter("id", planId).executeUpdate()
        }

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_plan SET content_hash = :hash WHERE id = :id")
                    .setParameter("hash", "b".repeat(64)).setParameter("id", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery(
                    "UPDATE provisioning_step_attribute SET attribute_value = '111' WHERE step_id IN (SELECT id FROM provisioning_step WHERE plan_id = :plan)",
                ).setParameter("plan", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_plan SET status = 'GENERATED' WHERE id = :id")
                    .setParameter("id", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_step SET operation = 'VERIFY_STATE' WHERE plan_id = :plan")
                    .setParameter("plan", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        asTenant(tenantId) { insertExecution(tenantId, planId, "same-key") }
        assertThatThrownBy { asTenant(tenantId) { insertExecution(tenantId, planId, "same-key") } }
            .isInstanceOf(ConstraintViolationException::class.java)
    }

    @Test
    fun `saving an existing generated plan cannot replace its persisted payload`() {
        val tenantId = tenant("provision-repeat")
        val intentId = asTenant(tenantId) { insertIntent(tenantId, insertPool(tenantId)) }
        val originalStep = ProvisionStep.create(
            1,
            DeviceReference(DeviceKind.ROUTER, FIXED_DEVICE),
            ProvisionOperation.ENSURE_TAGGED_VLAN,
            mapOf("vlanId" to "110"),
        )
        val original = ProvisionPlan.generate(tenantId, intentId, 1, listOf(originalStep))
        asTenant(tenantId) { provisionPlans.save(original) }

        val alternativeStep = ProvisionStep.create(
            1,
            DeviceReference(DeviceKind.ROUTER, FIXED_DEVICE),
            ProvisionOperation.VERIFY_STATE,
            mapOf("vlanId" to "120"),
        )
        val alternativeHash = ProvisionPlan.generate(tenantId, intentId, 1, listOf(alternativeStep)).contentHash
        val sameIdentityAlternative = ProvisionPlan.rehydrate(
            original.id,
            tenantId,
            intentId,
            1,
            listOf(alternativeStep),
            PlanStatus.GENERATED,
            alternativeHash,
        )

        asTenant(tenantId) { provisionPlans.save(sameIdentityAlternative) }
        val stored = asTenant(tenantId) { provisionPlans.findById(original.id)!! }

        assertThat(stored.contentHash).isEqualTo(original.contentHash)
        assertThat(stored.steps).hasSize(1)
        assertThat(stored.steps.single().operation).isEqualTo(ProvisionOperation.ENSURE_TAGGED_VLAN)
        assertThat(stored.steps.single().attributes).containsEntry("vlanId", "110")
    }

    @Test
    fun `execution persistence atomically reuses an idempotency key`() {
        val tenantId = tenant("provision-replay")
        val (intentId, planId) = asTenant(tenantId) {
            val intentId = insertIntent(tenantId, insertPool(tenantId))
            intentId to insertPlan(tenantId, intentId)
        }
        val first = ProvisionExecution.queue(tenantId, intentId, planId, "same-replay-key")
        val second = ProvisionExecution.queue(tenantId, intentId, planId, "same-replay-key")

        val persisted = asTenant(tenantId) { executions.save(first) }
        val replayed = asTenant(tenantId) { executions.save(second) }

        assertThat(replayed.id).isEqualTo(persisted.id)
        val differentPlan = asTenant(tenantId) { insertPlan(tenantId, intentId, 2) }
        assertThatThrownBy {
            asTenant(tenantId) {
                executions.save(ProvisionExecution.queue(tenantId, intentId, differentPlan, "same-replay-key"))
            }
        }.isInstanceOf(com.duluin.ftth.common.domain.error.ConflictException::class.java)
            .hasMessageContaining("EXECUTION_IDEMPOTENCY_KEY_REUSED")
    }

    @Test
    fun `concurrent execution replay converges on one persisted execution`() {
        val tenantId = tenant("provision-concurrent-replay")
        val (intentId, planId) = asTenant(tenantId) {
            val intentId = insertIntent(tenantId, insertPool(tenantId))
            intentId to insertPlan(tenantId, intentId)
        }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                workers.submit<UUID> {
                    ready.countDown()
                    start.await()
                    asTenant(tenantId) {
                        executions.save(ProvisionExecution.queue(tenantId, intentId, planId, "concurrent-replay")).id
                    }
                }
            }
            ready.await()
            start.countDown()
            assertThat(results.map { it.get() }.toSet()).hasSize(1)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `persistence rejects aggregate tenant ownership mismatch`() {
        val tenantA = tenant("provision-owner-a")
        val tenantB = tenant("provision-owner-b")
        val plan = ProvisionPlan.generate(tenantA, UuidV7.generate(), 1, listOf(
            ProvisionStep.create(
                1,
                DeviceReference(DeviceKind.ROUTER, FIXED_DEVICE),
                ProvisionOperation.ENSURE_TAGGED_VLAN,
                mapOf("vlanId" to "110"),
            ),
        ))

        assertThatThrownBy { asTenant(tenantB) { provisionPlans.save(plan) } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("TENANT_OWNERSHIP_MISMATCH")
    }

    @Test
    fun `database prevents referenced allocation deactivation deletion and reassignment`() {
        val tenantId = tenant("provision-reference-guard")
        val allocationId = UuidV7.generate()
        val secondAllocationId = UuidV7.generate()
        val (poolId, intentId) = asTenant(tenantId) {
            val poolId = insertPool(tenantId)
            val intentId = insertIntent(tenantId, poolId)
            insertAllocation(tenantId, allocationId, poolId, intentId, 110)
            insertAllocation(tenantId, secondAllocationId, poolId, intentId, 111)
            em.createNativeQuery(
                """INSERT INTO provisioning_vlan_allocation_reference
                   (id, tenant_id, allocation_id, reference_kind, reference_id)
                   VALUES (:id, :tenant, :allocation, 'SERVICE_INTENT', :reference)""",
            ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                .setParameter("allocation", allocationId).setParameter("reference", UuidV7.generate()).executeUpdate()
            poolId to intentId
        }

        listOf(
            "UPDATE provisioning_vlan_allocation SET active = false WHERE id = :id",
            "DELETE FROM provisioning_vlan_allocation WHERE id = :id",
            "UPDATE provisioning_vlan_allocation_reference SET allocation_id = '$secondAllocationId' WHERE allocation_id = :id",
            "UPDATE provisioning_vlan_allocation_reference SET id = '${UuidV7.generate()}' WHERE allocation_id = :id",
        ).forEach { sql ->
            assertThatThrownBy { asTenant(tenantId) { em.createNativeQuery(sql).setParameter("id", allocationId).executeUpdate() } }
                .isInstanceOf(ConstraintViolationException::class.java)
        }
        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_vlan_allocation SET id = :replacement WHERE id = :id")
                    .setParameter("replacement", UuidV7.generate()).setParameter("id", secondAllocationId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
        listOf(false to 0, true to 1).forEach { (active, references) ->
            assertThatThrownBy {
                asTenant(tenantId) {
                    em.createNativeQuery(
                        """INSERT INTO provisioning_vlan_allocation
                           (id, tenant_id, pool_id, device_kind, device_id, vlan_id, intent_id, active, reference_count)
                           VALUES (:id, :tenant, :pool, 'ROUTER', :device, :vlan, :intent, :active, :references)""",
                    ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId).setParameter("pool", poolId)
                        .setParameter("device", UuidV7.generate()).setParameter("vlan", 112 + references)
                        .setParameter("intent", intentId).setParameter("active", active)
                        .setParameter("references", references).executeUpdate()
                }
            }.isInstanceOf(ConstraintViolationException::class.java)
        }
    }

    @Test
    fun `database rejects non normalized snapshot text values`() {
        val tenantId = tenant("provision-normalized-json")
        val planId = asTenant(tenantId) {
            val intentId = insertIntent(tenantId, insertPool(tenantId))
            insertPlan(tenantId, intentId)
        }

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_device_snapshot
                       (id, tenant_id, device_kind, device_id, plan_id, normalized_state, captured_at)
                       VALUES (:id, :tenant, 'ROUTER', :device, :plan, CAST(:state AS jsonb), now())""",
                ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                    .setParameter("device", FIXED_DEVICE).setParameter("plan", planId)
                    .setParameter("state", "{\"name\":\"/interface vlan add\"}").executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
    }

    @Test
    fun `database rejects invalid initial states mismatched execution intent and empty plan validation`() {
        val tenantId = tenant("provision-state-guards")
        val (intentId, otherIntentId, planId) = asTenant(tenantId) {
            val poolId = insertPool(tenantId)
            val intentId = insertIntent(tenantId, poolId)
            val otherIntentId = insertIntent(tenantId, poolId)
            Triple(intentId, otherIntentId, insertPlan(tenantId, intentId))
        }

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_plan SET status = 'VALIDATED' WHERE id = :id")
                    .setParameter("id", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_plan
                       (id, tenant_id, intent_id, revision, status, content_hash)
                       VALUES (:id, :tenant, :intent, 2, 'VALIDATED', :hash)""",
                ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                    .setParameter("intent", intentId).setParameter("hash", "0".repeat(64)).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_execution
                       (id, tenant_id, intent_id, plan_id, idempotency_key, status, detail)
                       VALUES (:id, :tenant, :intent, :plan, :key, 'SUCCEEDED', NULL)""",
                ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                    .setParameter("intent", intentId).setParameter("plan", planId)
                    .setParameter("key", "invalid-terminal").executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery(
                    """INSERT INTO provisioning_execution
                       (id, tenant_id, intent_id, plan_id, idempotency_key, status)
                       VALUES (:id, :tenant, :intent, :plan, :key, 'QUEUED')""",
                ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                    .setParameter("intent", otherIntentId).setParameter("plan", planId)
                    .setParameter("key", "mismatched-intent").executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
    }

    @Test
    fun `database enforces the service intent lifecycle and immutable identity`() {
        val tenantId = tenant("provision-intent-lifecycle")
        val poolId = asTenant(tenantId) { insertPool(tenantId) }
        val profileId = asTenant(tenantId) { insertProfile(tenantId, poolId) }
        val intentId = UuidV7.generate()

        assertThatThrownBy {
            asTenant(tenantId) {
                insertIntentRow(tenantId, intentId, profileId, "ACTIVE")
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        asTenant(tenantId) {
            insertIntentRow(tenantId, intentId, profileId, "DRAFT")
            updateIntentStatus(intentId, "ACTIVE")
            updateIntentStatus(intentId, "SUSPENDED")
            updateIntentStatus(intentId, "ACTIVE")
            updateIntentStatus(intentId, "DECOMMISSIONED")
        }

        assertThatThrownBy {
            asTenant(tenantId) { updateIntentStatus(intentId, "ACTIVE") }
        }.isInstanceOf(ConstraintViolationException::class.java)
        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_service_intent SET subscription_id = :subscription WHERE id = :id")
                    .setParameter("subscription", UuidV7.generate()).setParameter("id", intentId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_service_intent SET id = :replacement WHERE id = :id")
                    .setParameter("replacement", UuidV7.generate()).setParameter("id", intentId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        listOf(
            "DRAFT" to "SUSPENDED",
            "ACTIVE" to "DRAFT",
            "SUSPENDED" to "DRAFT",
        ).forEach { (source, target) ->
            val candidateId = UuidV7.generate()
            asTenant(tenantId) {
                insertIntentRow(tenantId, candidateId, profileId, "DRAFT")
                if (source == "ACTIVE" || source == "SUSPENDED") updateIntentStatus(candidateId, "ACTIVE")
                if (source == "SUSPENDED") updateIntentStatus(candidateId, "SUSPENDED")
            }
            assertThatThrownBy { asTenant(tenantId) { updateIntentStatus(candidateId, target) } }
                .isInstanceOf(ConstraintViolationException::class.java)
        }
    }

    @Test
    fun `database plan attributes match domain normalized value rules`() {
        val tenantId = tenant("provision-attribute-parity")
        val planId = asTenant(tenantId) {
            val intentId = insertIntent(tenantId, insertPool(tenantId))
            insertPlan(tenantId, intentId)
        }
        val stepId = asTenant(tenantId) {
            UuidV7.generate().also { id ->
                em.createNativeQuery(
                    """INSERT INTO provisioning_step
                       (id, tenant_id, plan_id, step_order, device_kind, device_id, operation)
                       VALUES (:id, :tenant, :plan, 1, 'ROUTER', :device, 'ENSURE_TAGGED_VLAN')""",
                ).setParameter("id", id).setParameter("tenant", tenantId).setParameter("plan", planId)
                    .setParameter("device", FIXED_DEVICE).executeUpdate()
            }
        }

        listOf(
            "payload" to "normalized-value",
            "interface" to "secret-label",
            "interface" to "/interface vlan add name=vlan110",
            "interface" to "-----begin",
        ).forEach { (key, value) ->
            assertThatThrownBy {
                asTenant(tenantId) {
                    em.createNativeQuery(
                        """INSERT INTO provisioning_step_attribute
                           (id, tenant_id, step_id, attribute_key, attribute_value)
                           VALUES (:id, :tenant, :step, :key, :value)""",
                    ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                        .setParameter("step", stepId).setParameter("key", key).setParameter("value", value).executeUpdate()
                }
            }.isInstanceOf(ConstraintViolationException::class.java)
        }
    }

    @Test
    fun `database rejects illegal direct plan and execution transitions`() {
        val tenantId = tenant("provision-transition-guards")
        val (intentId, planId) = asTenant(tenantId) {
            val intentId = insertIntent(tenantId, insertPool(tenantId))
            intentId to insertPlan(tenantId, intentId)
        }

        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_plan SET status = 'SUPERSEDED' WHERE id = :id")
                    .setParameter("id", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_plan SET id = :replacement WHERE id = :id")
                    .setParameter("replacement", UuidV7.generate()).setParameter("id", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        val executionId = asTenant(tenantId) {
            UuidV7.generate().also { id ->
                em.createNativeQuery(
                    """INSERT INTO provisioning_execution
                       (id, tenant_id, intent_id, plan_id, idempotency_key, status)
                       VALUES (:id, :tenant, :intent, :plan, :key, 'QUEUED')""",
                ).setParameter("id", id).setParameter("tenant", tenantId).setParameter("intent", intentId)
                    .setParameter("plan", planId).setParameter("key", "illegal-transition").executeUpdate()
            }
        }
        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_execution SET status = 'SUCCEEDED' WHERE id = :id")
                    .setParameter("id", executionId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_execution SET detail = 'out-of-band edit' WHERE id = :id")
                    .setParameter("id", executionId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
        assertThatThrownBy {
            asTenant(tenantId) {
                em.createNativeQuery("UPDATE provisioning_execution SET id = :replacement WHERE id = :id")
                    .setParameter("replacement", UuidV7.generate()).setParameter("id", executionId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)
    }

    private fun insertPoolAndAllocation(tenantId: UUID, allocationId: UUID) {
        val poolId = insertPool(tenantId)
        val intentId = insertIntent(tenantId, poolId)
        insertAllocation(tenantId, allocationId, poolId, intentId, 110)
    }

    private fun insertPool(tenantId: UUID): UUID {
        val poolId = UuidV7.generate()
        em.createNativeQuery(
            """INSERT INTO provisioning_vlan_pool (id, tenant_id, name, vlan_start, vlan_end)
               VALUES (:id, :tenant, :name, 100, 199)""",
        ).setParameter("id", poolId).setParameter("tenant", tenantId)
            .setParameter("name", "POP-${UUID.randomUUID()}").executeUpdate()
        return poolId
    }

    private fun insertIntent(tenantId: UUID, poolId: UUID): UUID {
        val profileId = insertProfile(tenantId, poolId)
        val intentId = UuidV7.generate()
        insertIntentRow(tenantId, intentId, profileId, "DRAFT")
        updateIntentStatus(intentId, "ACTIVE")
        return intentId
    }

    private fun insertProfile(tenantId: UUID, poolId: UUID): UUID {
        val profileId = UuidV7.generate()
        em.createNativeQuery(
            """INSERT INTO provisioning_segment_profile (id, tenant_id, name, pool_id)
               VALUES (:id, :tenant, :name, :pool)""",
        ).setParameter("id", profileId).setParameter("tenant", tenantId)
            .setParameter("name", "Profile-${UUID.randomUUID()}").setParameter("pool", poolId).executeUpdate()
        return profileId
    }

    private fun insertIntentRow(tenantId: UUID, intentId: UUID, profileId: UUID, status: String) {
        em.createNativeQuery(
            """INSERT INTO provisioning_service_intent
               (id, tenant_id, subscription_id, segment_profile_id, encapsulation, status)
               VALUES (:id, :tenant, :subscription, :profile, 'SINGLE_TAG', :status)""",
        ).setParameter("id", intentId).setParameter("tenant", tenantId)
            .setParameter("subscription", UuidV7.generate()).setParameter("profile", profileId)
            .setParameter("status", status).executeUpdate()
    }

    private fun updateIntentStatus(intentId: UUID, status: String) {
        em.createNativeQuery("UPDATE provisioning_service_intent SET status = :status WHERE id = :id")
            .setParameter("status", status).setParameter("id", intentId).executeUpdate()
    }

    private fun insertAllocation(tenantId: UUID, id: UUID, poolId: UUID, intentId: UUID, vlanId: Int) {
        em.createNativeQuery(
            """INSERT INTO provisioning_vlan_allocation
               (id, tenant_id, pool_id, device_kind, device_id, vlan_id, intent_id, active, reference_count)
               VALUES (:id, :tenant, :pool, 'ROUTER', :device, :vlan, :intent, true, 0)""",
        ).setParameter("id", id).setParameter("tenant", tenantId).setParameter("pool", poolId)
            .setParameter("device", FIXED_DEVICE).setParameter("vlan", vlanId)
            .setParameter("intent", intentId).executeUpdate()
    }

    private fun existingPool(tenantId: UUID): UUID =
        em.createNativeQuery("SELECT pool_id FROM provisioning_vlan_allocation WHERE tenant_id = :tenant LIMIT 1")
            .setParameter("tenant", tenantId).singleResult as UUID

    private fun existingIntent(tenantId: UUID): UUID =
        em.createNativeQuery("SELECT intent_id FROM provisioning_vlan_allocation WHERE tenant_id = :tenant LIMIT 1")
            .setParameter("tenant", tenantId).singleResult as UUID

    private fun insertExecution(tenantId: UUID, planId: UUID, key: String) {
        em.createNativeQuery(
            """INSERT INTO provisioning_execution (id, tenant_id, plan_id, idempotency_key, status)
               VALUES (:id, :tenant, :plan, :key, 'QUEUED')""",
        ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
            .setParameter("plan", planId).setParameter("key", key).executeUpdate()
    }

    private fun insertPlan(tenantId: UUID, intentId: UUID, revision: Int = 1): UUID = UuidV7.generate().also { planId ->
        em.createNativeQuery(
            """INSERT INTO provisioning_plan (id, tenant_id, intent_id, revision, status, content_hash)
               VALUES (:id, :tenant, :intent, :revision, 'GENERATED', :hash)""",
        ).setParameter("id", planId).setParameter("tenant", tenantId)
            .setParameter("intent", intentId).setParameter("revision", revision)
            .setParameter("hash", "0".repeat(64)).executeUpdate()
    }

    private fun tenant(prefix: String) = tenantApi.ensureTenant(
        "$prefix-${UUID.randomUUID().toString().take(8)}",
        prefix,
    ).id

    private fun count(tenantId: UUID, table: String, id: UUID, column: String = "id"): Long = asTenant(tenantId) {
        (em.createNativeQuery("SELECT count(*) FROM $table WHERE $column = :id")
            .setParameter("id", id).singleResult as Number).toLong()
    }

    private fun number(sql: String, id: UUID): Long =
        (em.createNativeQuery(sql).setParameter("id", id).singleResult as Number).toLong()

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }

    companion object {
        private val FIXED_DEVICE: UUID = UUID.fromString("00000000-0000-7000-8000-000000000001")
    }
}
