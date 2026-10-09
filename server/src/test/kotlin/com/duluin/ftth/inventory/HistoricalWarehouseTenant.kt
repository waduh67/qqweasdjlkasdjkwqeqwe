package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

internal fun historicalWarehouseTenant(context: ConfigurableApplicationContext, slug: String, name: String): UUID {
    val tenant = UUID.randomUUID()
    TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
        context.getBean(TenantTransactionJdbc::class.java).withinTenant(tenant) { connection ->
            connection.prepareStatement("INSERT INTO tenant(id,slug,name) VALUES (?,?,?)").use {
                it.setObject(1, tenant); it.setString(2, slug); it.setString(3, name); it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO iam_authorization_epoch(id,tenant_id) VALUES (?,?)").use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2, tenant); it.executeUpdate()
            }
            connection.prepareStatement("""
                INSERT INTO inventory_tenant_cutover(id,tenant_id,state,initialization_kind,activation_at)
                VALUES (?,?,'ENFORCED','NEW_EMPTY',warehouse_activation_at())
            """.trimIndent()).use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2, tenant); it.executeUpdate()
            }
        }
    }
    return tenant
}
