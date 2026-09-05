package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.provisioning.application.port.outbound.ProvisionPlanRepository
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.PlanStatus
import com.duluin.ftth.provisioning.domain.model.ProvisionOperation
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.model.ProvisionStep
import com.duluin.ftth.tenancy.TenantApi
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
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
class ProvisioningPlanningConcurrencyIT {
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var plans: ProvisionPlanRepository
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @PersistenceContext private lateinit var entityManager: EntityManager

    @Test
    fun `intent planning lock serializes first revision creation and keeps revisions monotonic`() {
        val tenantId = tenantApi.ensureTenant("plan-lock-${UUID.randomUUID()}", "plan-lock").id
        val intentId = asTenant(tenantId) { insertIntent(tenantId) }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val revisions = List(2) { worker ->
                workers.submit<Int> {
                    ready.countDown()
                    start.await()
                    asTenant(tenantId) {
                        plans.lockIntent(intentId)
                        val current = plans.findLatestByIntentId(intentId)
                        current?.takeIf { it.status == PlanStatus.GENERATED }?.let {
                            it.reject()
                            plans.save(it)
                        }
                        Thread.sleep(75)
                        val revision = (current?.revision ?: 0) + 1
                        val step = ProvisionStep.create(
                            1,
                            DeviceReference(DeviceKind.ROUTER, UUID.nameUUIDFromBytes("planner-$worker".toByteArray())),
                            ProvisionOperation.ENSURE_TAGGED_VLAN,
                            mapOf("vlanId" to (110 + worker).toString()),
                        )
                        plans.save(ProvisionPlan.generate(tenantId, intentId, revision, listOf(step)))
                        revision
                    }
                }
            }
            ready.await()
            start.countDown()

            assertThat(revisions.map { it.get() }).containsExactlyInAnyOrder(1, 2)
            assertThat(asTenant(tenantId) { plans.findLatestByIntentId(intentId)!!.revision }).isEqualTo(2)
        } finally {
            workers.shutdownNow()
        }
    }

    private fun insertIntent(tenantId: UUID): UUID {
        val poolId = UuidV7.generate()
        entityManager.createNativeQuery(
            "INSERT INTO provisioning_vlan_pool (id, tenant_id, name, vlan_start, vlan_end) VALUES (:id, :tenant, 'Plan Lock', 100, 199)",
        ).setParameter("id", poolId).setParameter("tenant", tenantId).executeUpdate()
        val profileId = UuidV7.generate()
        entityManager.createNativeQuery(
            "INSERT INTO provisioning_segment_profile (id, tenant_id, name, pool_id) VALUES (:id, :tenant, 'Plan Lock', :pool)",
        ).setParameter("id", profileId).setParameter("tenant", tenantId).setParameter("pool", poolId).executeUpdate()
        return UuidV7.generate().also { intentId ->
            entityManager.createNativeQuery(
                """INSERT INTO provisioning_service_intent
                   (id, tenant_id, subscription_id, segment_profile_id, encapsulation, status)
                   VALUES (:id, :tenant, :subscription, :profile, 'SINGLE_TAG', 'DRAFT')""",
            ).setParameter("id", intentId).setParameter("tenant", tenantId)
                .setParameter("subscription", UuidV7.generate()).setParameter("profile", profileId).executeUpdate()
        }
    }

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
