package com.duluin.ftth.inventory

import com.duluin.ftth.iam.application.port.outbound.PermissionRepository
import com.duluin.ftth.iam.application.service.PermissionCatalogSeeder
import com.duluin.ftth.inventory.application.service.InventoryApprovalService
import com.duluin.ftth.inventory.domain.model.ApproverDelegation
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WarehouseConcurrencyITCatalog {
    @Test fun `global permission change invalidates active and suspended tenants once and rolls back`() {
        WarehouseSchemaDatabase().use { database -> postingContext(database).use { context ->
            val first = WarehousePostingFixture(context)
            val second = WarehousePostingFixture(context)
            context.getBean(com.duluin.ftth.tenancy.TenantApi::class.java).suspend(second.tenant)
            val before = listOf(first, second).map { it.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() } }
            first.transaction {
                val permissions = context.getBean(PermissionRepository::class.java)
                val permission = permissions.findAll().first { it.code.value == "inventory.transfer.manage" }
                permission.deactivate(); permissions.save(permission); permissions.save(permission)
            }
            listOf(first, second).forEachIndexed { index, fixture ->
                assertThat(fixture.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }).isEqualTo(before[index] + 1)
            }
            assertThatThrownBy { first.transaction {
                context.getBean(PermissionCatalogSeeder::class.java).sync(); error("rollback catalog")
            } }.isInstanceOf(IllegalStateException::class.java)
            assertThat(first.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }).isEqualTo(before.first() + 1)
            assertThat(first.transaction { scalar("SELECT active FROM permission WHERE code='inventory.transfer.manage'") }).isEqualTo("f")
            first.transaction { context.getBean(PermissionCatalogSeeder::class.java).sync() }
            val synced = first.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }
            first.transaction { context.getBean(PermissionCatalogSeeder::class.java).sync() }
            assertThat(first.transaction { scalar("SELECT epoch FROM iam_authorization_epoch").toLong() }).isEqualTo(synced)
        } }
    }

    @Test fun `delegation cannot create memory authority regardless of expiry`() {
        val service = InventoryApprovalService()
        listOf(Instant.EPOCH, Instant.now().plusSeconds(3600)).forEach { expiry ->
            assertThatThrownBy { service.registerDelegation(ApproverDelegation(UUID.randomUUID(), UUID.randomUUID(), expiry)) }
                .isInstanceOfSatisfying(WarehouseContractException::class.java) {
                    assertThat(it.error.code).isEqualTo(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
                }
        }
    }
}
