package com.duluin.ftth.customer

import com.duluin.ftth.inventory.AcceptAssetHandoverRequest
import com.duluin.ftth.inventory.InventoryDeploymentApi
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class CustomerAssetOwnershipIntegrityCases : CustomerAssetOwnershipFixture() {
    @Test
    fun `fabricated acceptance without an owner operation rejects at commit`() {
        val case = ownershipCase()

        assertThrows<Exception> {
            fixture(case.installation.receipt.stock.token).transaction {
                sql("""INSERT INTO inventory_asset_handover(id,tenant_id,assignment_id,asset_id,work_order_id,customer_id,
                    actor_id,evidence_id,evidence_reference,ownership_mode,accepted_at,assignment_revision)
                    SELECT '${UUID.randomUUID()}',tenant_id,id,asset_id,work_order_id,customer_id,actor_id,
                    '${case.signature}','fabricated-reference',ownership_mode,clock_timestamp(),revision
                    FROM inventory_asset_assignment WHERE id='${case.installation.operation}'""")
            }
        }

        assertThat(title(case)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|0|0")
    }

    @ParameterizedTest
    @ValueSource(strings = ["CLEARED", "MISMATCHED", "RESTORED", "SELECTIVE"])
    fun `deferred acceptance validates its own tenant scope`(timing: String) {
        val case = ownershipCase()
        val stock = fixture(case.installation.receipt.stock.token)
        val operation = {
            authenticated(case.installation) {
                context.getBean(InventoryDeploymentApi::class.java).acceptHandover(
                    AcceptAssetHandoverRequest(case.installation.operation, 0, case.signature), WarehouseMutationMetadata("tenant-timing"))
                if (timing == "SELECTIVE") stock.sql("SET CONSTRAINTS warehouse_deployment_final IMMEDIATE")
                stock.sql("SELECT set_config('app.tenant_id','${if (timing == "CLEARED") "" else UUID.randomUUID()}',true)")
                if (timing == "RESTORED") stock.sql("SELECT set_config('app.tenant_id','${stock.tenant}',true)")
            }
        }

        if (timing == "RESTORED") operation() else assertThrows<Exception> { operation() }

        assertThat(title(case)).isEqualTo(if (timing == "RESTORED") "LOAN|ISP|ISP|CUSTOMER_INSTALLED|1|1" else "LOAN|ISP|ISP|CUSTOMER_INSTALLED|0|0")
    }

    @Test
    fun `simultaneous duplicate acceptance returns one durable response`() {
        val case = ownershipCase("SALE")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)

        val responses = Executors.newFixedThreadPool(2).use { executor ->
            val futures = List(2) {
                executor.submit<org.springframework.mock.web.MockHttpServletResponse> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    accept(case)
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        }

        assertThat(responses.map { it.status }).withFailMessage(responses.joinToString("\n") { "${it.status}: ${it.contentAsString}" })
            .containsExactly(200, 200)
        assertThat(responses.map { it.contentAsString }.distinct()).hasSize(1)
        assertThat(title(case)).isEqualTo("SALE|CUSTOMER|CUSTOMER|CUSTOMER_INSTALLED|1|1")
    }

    @Test
    fun `removed warehouse scope denies handover with the original JWT`() {
        val case = ownershipCase()
        val receipt = case.installation.receipt
        val revoked = request("PUT", "/api/v1/warehouse/settings/scopes/${receipt.receiver.second}/${receipt.field}", receipt.stock.token,
            """{"expectedRevision":1,"active":false}""")
        assertThat(revoked.status).withFailMessage(revoked.contentAsString).isEqualTo(200)

        val accepted = accept(case)

        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(404)
        assertThat(title(case)).isEqualTo("LOAN|ISP|ISP|CUSTOMER_INSTALLED|0|0")
    }
}
