package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.tenancy.TenantApi
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class WarehouseBootstrapIT : WarehouseMasterHttpFixture() {
    @Autowired private lateinit var tenants: TenantApi
    @Autowired private lateinit var jdbc: TenantTransactionJdbc
    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Test
    fun `legacy work inserted after bootstrap still rolls back the entire tenant creation`() {
        val slug = "late-work-${UUID.randomUUID()}"
        assertThatThrownBy {
            TransactionTemplate(transactions).executeWithoutResult {
                val tenant = tenants.ensureTenant(slug, "Late work")
                jdbc.withinTenant(tenant.id) { connection ->
                    connection.prepareStatement("""
                        INSERT INTO work_order(id,tenant_id,code,type,title,created_by)
                        VALUES (?,?,?,'REPAIR','Legacy task',?)
                    """.trimIndent()).use {
                        it.setObject(1, UUID.randomUUID()); it.setObject(2, tenant.id)
                        it.setString(3, UUID.randomUUID().toString().take(20)); it.setObject(4, UUID.randomUUID())
                        it.executeUpdate()
                    }
                    connection.createStatement().use { it.execute("SET CONSTRAINTS ALL IMMEDIATE") }
                }
            }
        }.hasStackTraceContaining("reference workflow requires a reference work order")
        assertThat(tenants.findBySlug(slug)).isNull()
    }
}
