package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.adapter.outbound.persistence.PermissionJpaRepository
import com.duluin.ftth.iam.domain.catalog.PermissionCatalog
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

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningPermissionIT {
    @Autowired private lateinit var permissions: PermissionJpaRepository
    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager
    @PersistenceContext private lateinit var em: EntityManager

    @Test
    fun `provisioning permissions are seeded and only certification is platform only`() {
        val expectedTenantPermissions = setOf(
            "provisioning.segment.view",
            "provisioning.segment.manage",
            "provisioning.plan.view",
            "provisioning.execution.apply",
            "provisioning.execution.cancel",
            "provisioning.drift.view",
            "provisioning.drift.adopt",
        )
        val expectedPlatformPermission = "provisioning.certification.manage"
        val expectedAll = expectedTenantPermissions + expectedPlatformPermission
        val catalog = PermissionCatalog.ALL.associateBy { it.code.value }
        val seeded = permissions.findAll().associateBy { it.code }

        assertThat(catalog.keys.filter { it.startsWith("provisioning.") }.toSet()).isEqualTo(expectedAll)
        assertThat(seeded.keys.filter { it.startsWith("provisioning.") }.toSet()).isEqualTo(expectedAll)
        assertThat(expectedTenantPermissions).allSatisfy { code ->
            assertThat(catalog.getValue(code).platformOnly).isFalse()
            assertThat(seeded.getValue(code).platformOnly).isFalse()
        }
        assertThat(catalog.getValue(expectedPlatformPermission).platformOnly).isTrue()
        assertThat(seeded.getValue(expectedPlatformPermission).platformOnly).isTrue()
        assertThat(PermissionCatalog.tenantAssignable().map { it.code.value })
            .doesNotContain(expectedPlatformPermission)
    }

    @Test
    fun `tenant operator cannot see another tenant intent`() {
        val tenantA = tenant("policy-a")
        val tenantB = tenant("policy-b")
        val intentId = asTenant(tenantA) { insertIntent(tenantA) }

        assertThat(countIntent(tenantA, intentId)).isEqualTo(1)
        assertThat(countIntent(tenantB, intentId)).isZero()
    }

    private fun insertIntent(tenantId: UUID): UUID {
        val poolId = UuidV7.generate()
        em.createNativeQuery(
            """INSERT INTO provisioning_vlan_pool (id, tenant_id, name, vlan_start, vlan_end)
               VALUES (:id, :tenant, :name, 100, 199)""",
        ).setParameter("id", poolId).setParameter("tenant", tenantId)
            .setParameter("name", "Pool-${UUID.randomUUID()}").executeUpdate()
        val profileId = UuidV7.generate()
        em.createNativeQuery(
            """INSERT INTO provisioning_segment_profile (id, tenant_id, name, pool_id)
               VALUES (:id, :tenant, :name, :pool)""",
        ).setParameter("id", profileId).setParameter("tenant", tenantId)
            .setParameter("name", "Profile-${UUID.randomUUID()}").setParameter("pool", poolId).executeUpdate()
        return UuidV7.generate().also { intentId ->
            em.createNativeQuery(
                """INSERT INTO provisioning_service_intent
                   (id, tenant_id, subscription_id, segment_profile_id, encapsulation, status)
                   VALUES (:id, :tenant, :subscription, :profile, 'SINGLE_TAG', 'DRAFT')""",
            ).setParameter("id", intentId).setParameter("tenant", tenantId)
                .setParameter("subscription", UuidV7.generate()).setParameter("profile", profileId).executeUpdate()
            em.createNativeQuery("UPDATE provisioning_service_intent SET status = 'ACTIVE' WHERE id = :id")
                .setParameter("id", intentId).executeUpdate()
        }
    }

    private fun countIntent(tenantId: UUID, intentId: UUID): Long = asTenant(tenantId) {
        (em.createNativeQuery("SELECT count(*) FROM provisioning_service_intent WHERE id = :id")
            .setParameter("id", intentId).singleResult as Number).toLong()
    }

    private fun tenant(prefix: String) = tenantApi.ensureTenant(
        "$prefix-${UUID.randomUUID().toString().take(8)}",
        prefix,
    ).id

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T = TenantContext.runAs(tenantId) {
        TransactionTemplate(txManager).execute { block() }!!
    }
}
