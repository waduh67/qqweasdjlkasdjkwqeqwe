package com.duluin.ftth.inventory

import com.duluin.ftth.FtthApplication
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseDocumentJpaEntity
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseSkuJpaEntity
import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

class WarehouseSchemaITRestart {
    @Test
    fun `fresh Spring process contexts read identical committed policy master and document`() {
        WarehouseSchemaDatabase().use { database ->
            lateinit var tenant: UUID
            val sku = UUID.randomUUID()
            val document = UUID.randomUUID()
            start(database).use { context ->
                tenant = context.getBean(com.duluin.ftth.tenancy.TenantApi::class.java)
                    .ensureTenant("restart-${UUID.randomUUID()}", "Restart").id
                transaction(context, tenant) {
                    assertThat(context.getBean(InventoryTenantPolicyService::class.java).read().state).isEqualTo(WarehouseCutoverState.ENFORCED)
                    val entityManager = requireNotNull(EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory::class.java)))
                    entityManager.persist(WarehouseSkuJpaEntity(sku, "restart-cable", "Restart cable", WarehouseTracking.LOT, WarehouseBaseUnit.MM))
                    entityManager.persist(WarehouseDocumentJpaEntity(document, "restart-draft", WarehouseDocumentKind.DEMAND, UUID.randomUUID(), 0, 0))
                }
            }
            start(database).use { context ->
                transaction(context, tenant) {
                    val policy = context.getBean(InventoryTenantPolicyService::class.java).read()
                    assertThat(policy.state).isEqualTo(WarehouseCutoverState.ENFORCED)
                    assertThat(policy.epoch).isZero()
                    val entityManager = requireNotNull(EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory::class.java)))
                    assertThat(entityManager.find(WarehouseSkuJpaEntity::class.java, sku).name).isEqualTo("Restart cable")
                    assertThat(entityManager.find(WarehouseDocumentJpaEntity::class.java, document).code).isEqualTo("restart-draft")
                    assertThat(entityManager.createNativeQuery("SELECT current_user", String::class.java).singleResult).isEqualTo("warehouse_app")
                    assertThat(entityManager.createNativeQuery("SELECT epoch FROM iam_authorization_epoch WHERE tenant_id=:tenant", Long::class.java)
                        .setParameter("tenant",tenant).singleResult).isEqualTo(0L)
                }
            }
        }
    }

    private fun start(database: WarehouseSchemaDatabase): ConfigurableApplicationContext = SpringApplicationBuilder(FtthApplication::class.java)
        .profiles("test").run(
            "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=${database.url}", "--spring.flyway.url=${database.url}",
            "--spring.flyway.schemas=${database.schema}", "--spring.flyway.default-schema=${database.schema}",
            "--ftth.bootstrap.seed-demo-tenant=false",
        )

    private fun transaction(context: ConfigurableApplicationContext, tenant: UUID, action: () -> Unit) = TenantContext.runAs(tenant) {
        TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult { action() }
    }
}
