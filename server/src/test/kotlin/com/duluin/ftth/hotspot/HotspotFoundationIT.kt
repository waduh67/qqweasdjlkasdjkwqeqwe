package com.duluin.ftth.hotspot

import com.duluin.ftth.common.tenant.TenantContext
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
class HotspotFoundationIT {

    @Autowired private lateinit var tenantApi: TenantApi
    @Autowired private lateinit var txManager: PlatformTransactionManager

    @PersistenceContext private lateinit var em: EntityManager

    private fun unique() = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    private fun <T> asTenant(tenantId: UUID, block: () -> T): T =
        TenantContext.runAs(tenantId) {
            TransactionTemplate(txManager).execute { block() }!!
        }

    @Test
    fun `hotspot permissions are registered`() {
        assertThat(PermissionCatalog.codes).contains(
            "hotspot.site.view",
            "hotspot.site.manage",
            "hotspot.voucher.view",
            "hotspot.voucher.manage",
            "hotspot.session.view",
        )
    }

    @Test
    fun `RLS hides hotspot rows owned by another tenant`() {
        val tenantA = tenantApi.ensureTenant("hotspot-a-${unique()}", "Hotspot A").id
        val tenantB = tenantApi.ensureTenant("hotspot-b-${unique()}", "Hotspot B").id
        val siteId = UUID.randomUUID()

        asTenant(tenantA) {
            em.createNativeQuery(
                """INSERT INTO hotspot_site (id, tenant_id, nas_id, portal_id, name, portal_mode)
                   VALUES (:id, :tenantId, :nasId, :portalId, :name, :portalMode)""",
            )
                .setParameter("id", siteId)
                .setParameter("tenantId", tenantA)
                .setParameter("nasId", UUID.randomUUID())
                .setParameter("portalId", "foundationport${unique()}")
                .setParameter("name", "Foundation site")
                .setParameter("portalMode", "OFF")
                .executeUpdate()
        }

        val visibleToA = asTenant(tenantA) {
            (em.createNativeQuery("SELECT count(*) FROM hotspot_site WHERE id = :id")
                .setParameter("id", siteId)
                .singleResult as Number).toLong()
        }
        val visibleToB = asTenant(tenantB) {
            (em.createNativeQuery("SELECT count(*) FROM hotspot_site WHERE id = :id")
                .setParameter("id", siteId)
                .singleResult as Number).toLong()
        }

        assertThat(visibleToA).isEqualTo(1)
        assertThat(visibleToB).isZero()
    }
}
