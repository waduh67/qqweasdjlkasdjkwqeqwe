package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.DurableInventoryFulfillmentService
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class WarehouseConcurrencyITLegacy {
    @Test fun `two nodes retrying the same legacy key receive one original movement`() {
        WarehouseSchemaDatabase().use { database -> postingContext(database).use { context ->
            val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
            val piece=fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) }
            val command=InventoryFulfillmentCommand(fixture.tenant,piece.stockIdentityId,piece.stockIdentityId,piece.skuId,piece.locationId,
                fixture.customer,fixture.workOrder,1,true,true,fixture.actor,"ignored","parallel-key","ignored","Use","ONU")
            val start=java.util.concurrent.CountDownLatch(1)
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val futures=(1..2).map { pool.submit<com.duluin.ftth.inventory.domain.model.InventoryMovement> {
                    fixture.authenticateLegacy(); check(start.await(10,java.util.concurrent.TimeUnit.SECONDS))
                    fixture.transaction { context.getBean(com.duluin.ftth.inventory.application.service.WarehouseCommandService::class.java).executeLegacy(command,false) }
                } }
                start.countDown()
                val results=futures.map { it.get(20,java.util.concurrent.TimeUnit.SECONDS) }
                assertThat(results[0]).isEqualTo(results[1])
                fixture.transaction {
                    assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.legacy.consume'")).isEqualTo("1")
                    assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("1")
                }
            } finally { pool.shutdownNow(); pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS); SecurityContextHolder.clearContext() }
        } }
    }

    @Test fun `legacy command ignores supplied hash and replays after loss but denies revocation and actor spoof`() {
        WarehouseSchemaDatabase().use { database -> postingContext(database).use { context ->
            val fixture = WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
            try {
                val piece = fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) }
                val command = InventoryFulfillmentCommand(fixture.tenant,piece.stockIdentityId,piece.stockIdentityId,piece.skuId,piece.locationId,
                    fixture.customer,fixture.workOrder,1,true,true,fixture.actor,"client.namespace","legacy-replay","untrusted-hash","Consume","ONU")
                val service = context.getBean(DurableInventoryFulfillmentService::class.java)
                val first = fixture.transaction { service.apply(command, false) }
                val before = fixture.transaction { counts() }
                assertThat(fixture.transaction { service.apply(command.copy(payloadHash="different-untrusted-hash"), false) }).isEqualTo(first)
                assertThat(fixture.transaction { counts() }).isEqualTo(before)
                assertThatThrownBy { fixture.transaction { service.apply(command.copy(actorId=UUID.randomUUID()), false) } }.hasMessageContaining("FORBIDDEN")
                assertThatThrownBy { fixture.transaction { service.apply(command.copy(reason="changed"), false) } }.hasMessageContaining("IDEMPOTENCY_CONFLICT")
                fixture.authenticateLegacy(UUID.randomUUID())
                fixture.transaction { context.getBean(com.duluin.ftth.iam.application.service.UserService::class.java).setEnabled(actor, false) }
                fixture.authenticateLegacy()
                assertThatThrownBy { fixture.transaction { service.apply(command, false) } }.isInstanceOf(com.duluin.ftth.common.domain.error.AccessDeniedException::class.java)
                SecurityContextHolder.clearContext()
                assertThatThrownBy { fixture.transaction { service.apply(command, false) } }.isInstanceOf(com.duluin.ftth.common.domain.error.AuthenticationException::class.java)
                assertThat(fixture.transaction { counts() }).isEqualTo(before)
            } finally { SecurityContextHolder.clearContext() }
        } }
    }
}
