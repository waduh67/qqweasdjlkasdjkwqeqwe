package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
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

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningPersistenceIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
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
            ).setParameter("id", UuidV7.generate()).setParameter("tenant", tenantId)
                .setParameter("plan", planId).setParameter("device", FIXED_DEVICE).executeUpdate()
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
                em.createNativeQuery("UPDATE provisioning_step SET operation = 'VERIFY_STATE' WHERE plan_id = :plan")
                    .setParameter("plan", planId).executeUpdate()
            }
        }.isInstanceOf(ConstraintViolationException::class.java)

        asTenant(tenantId) { insertExecution(tenantId, planId, "same-key") }
        assertThatThrownBy { asTenant(tenantId) { insertExecution(tenantId, planId, "same-key") } }
            .isInstanceOf(ConstraintViolationException::class.java)
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
        val profileId = UuidV7.generate()
        em.createNativeQuery(
            """INSERT INTO provisioning_segment_profile (id, tenant_id, name, pool_id)
               VALUES (:id, :tenant, :name, :pool)""",
        ).setParameter("id", profileId).setParameter("tenant", tenantId)
            .setParameter("name", "Profile-${UUID.randomUUID()}").setParameter("pool", poolId).executeUpdate()
        val intentId = UuidV7.generate()
        em.createNativeQuery(
            """INSERT INTO provisioning_service_intent
               (id, tenant_id, subscription_id, segment_profile_id, encapsulation, status)
               VALUES (:id, :tenant, :subscription, :profile, 'SINGLE_TAG', 'ACTIVE')""",
        ).setParameter("id", intentId).setParameter("tenant", tenantId)
            .setParameter("subscription", UuidV7.generate()).setParameter("profile", profileId).executeUpdate()
        return intentId
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
