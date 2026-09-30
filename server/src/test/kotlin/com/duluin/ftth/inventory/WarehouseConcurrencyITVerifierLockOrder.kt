package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.LegacyWarehousePostingPort
import com.duluin.ftth.inventory.application.service.WarehouseCommandService
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyITVerifierLockOrder {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `legacy document reference blocks before operation identity for execution and replay`(replay: Boolean) {
        val fixture=WarehousePostingFixture(context).also { it.setup(); it.legacyAuthority() }
        val piece=fixture.transaction { acknowledge(receipt(StockQuantity.each("1"))) }
        val command=InventoryFulfillmentCommand(fixture.tenant,piece.stockIdentityId,piece.stockIdentityId,piece.skuId,piece.locationId,
            fixture.customer,fixture.workOrder,1,true,true,fixture.actor,"ignored","lock-${UUID.randomUUID()}","ignored","Use","ONU")
        val source=fixture.transaction { context.getBean(LegacyWarehousePostingPort::class.java).reference(command) }
        val service=context.getBean(WarehouseCommandService::class.java)
        if(replay) fixture.transaction { service.executeLegacy(command,false) }
        val before=fixture.transaction { counts() }
        val held=CountDownLatch(1); val release=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val writer=pool.submit { fixture.transaction {
                scalar("SELECT id FROM inventory_document WHERE id='${source.document}' FOR UPDATE")
                if(replay) sql("UPDATE inventory_document SET revision=revision+1 WHERE id='${source.document}'")
                held.countDown(); check(release.await(25,TimeUnit.SECONDS))
            } }
            check(held.await(15,TimeUnit.SECONDS))
            val executing=pool.submit<Throwable?> { fixture.authenticateLegacy(); catchThrowable { fixture.transaction { service.executeLegacy(command,false) } } }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
            var blocked=false
            while(!blocked && System.nanoTime()<deadline) {
                blocked=fixture.transaction { scalar("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND wait_event_type='Lock' AND query LIKE '%inventory_document%'").toInt()>0 }
                Thread.yield()
            }
            assertThat(blocked).isTrue()
            fixture.transaction {
                assertThat(scalar("SELECT pg_try_advisory_xact_lock(hashtextextended('$tenant|warehouse.legacy.consume|${command.operationKey}',0))")).isEqualTo("t")
            }
            release.countDown(); writer.get(25,TimeUnit.SECONDS)
            val failure=executing.get(25,TimeUnit.SECONDS)
            if(replay) {
                assertThat(failure).isInstanceOf(WarehouseContractException::class.java).hasMessageContaining("STALE_REVISION")
                assertThat(fixture.transaction { counts() }).isEqualTo(before)
            } else {
                assertThat(failure).isNull()
                fixture.transaction { assertThat(scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.legacy.consume'")).isEqualTo("1") }
            }
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(10,TimeUnit.SECONDS) }
    }
}
