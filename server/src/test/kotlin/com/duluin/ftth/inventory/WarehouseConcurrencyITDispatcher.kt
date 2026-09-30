package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.outbound.PostingEvent
import com.duluin.ftth.inventory.application.service.WarehouseOutboxDispatcher
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyITDispatcher {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database=WarehouseSchemaDatabase(); context=postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }

    @Test fun `production dispatcher delivers outside transaction and fulfillment receipt survives restart`() {
        val fixture = WarehousePostingFixture(context).also { it.setup() }
        fixture.transaction { receipt(StockQuantity.each("1")) }
        val actual = context.getBean(WarehouseOutboxDeliveryPort::class.java)
        var externalCalls = 0
        val dispatcher = WarehouseOutboxDispatcher(context.getBean(WarehouseOutboxDeliveryStore::class.java),
            context.getBean(WarehouseDeliveryReader::class.java), object : WarehouseOutboxDeliveryPort {
                override fun deliver(message: WarehouseDeliveryMessage) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                    externalCalls++
                    actual.deliver(message)
                    actual.deliver(message)
                }
            }, context.getBean(com.duluin.ftth.tenancy.TenantApi::class.java))
        TenantContext.runAs(fixture.tenant) {
            assertThat(dispatcher.dispatchOne()).isTrue()
            assertThat(dispatcher.dispatchOne()).isFalse()
        }
        assertThat(externalCalls).isEqualTo(1)
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM fulfillment_warehouse_observation")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM inventory_outbox_delivery")).isEqualTo("DELIVERED")
        }
        context.close(); context=postingContext(database)
        TenantContext.runAs(fixture.tenant) {
            assertThat(context.getBean(WarehouseOutboxDispatcher::class.java).dispatchOne()).isFalse()
        }
        WarehousePostingFixture(context, fixture.tenant).transaction {
            assertThat(scalar("SELECT count(*) FROM fulfillment_warehouse_observation")).isEqualTo("1")
        }
    }

    @Test fun `real fulfillment insert failure rolls back inbox and effect then retries once`() {
        val fixture = WarehousePostingFixture(context).also { it.setup() }
        fixture.transaction { receipt(StockQuantity.each("1")) }
        database.ownerFixture { connection -> connection.createStatement().use {
            it.execute("CREATE FUNCTION qa_fail_observation() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''injected failure''; END'")
            it.execute("CREATE TRIGGER qa_fail BEFORE INSERT ON fulfillment_warehouse_observation FOR EACH ROW EXECUTE FUNCTION qa_fail_observation()")
        } }
        try {
            TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseOutboxDispatcher::class.java).dispatchOne() }
            fixture.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM fulfillment_warehouse_observation")).isEqualTo("0")
                assertThat(scalar("SELECT state FROM inventory_outbox_delivery")).isEqualTo("PENDING")
                assertThat(scalar("SELECT last_error FROM inventory_outbox_delivery")).isEqualTo("RETRYABLE")
            }
        } finally { database.ownerFixture { connection -> connection.createStatement().use {
            it.execute("DROP TRIGGER qa_fail ON fulfillment_warehouse_observation"); it.execute("DROP FUNCTION qa_fail_observation()")
        } } }
        fixture.transaction { sql("UPDATE inventory_outbox_delivery SET next_attempt_at=clock_timestamp(),revision=revision+1") }
        TenantContext.runAs(fixture.tenant) { context.getBean(WarehouseOutboxDispatcher::class.java).dispatchOne() }
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM fulfillment_warehouse_observation")).isEqualTo("1")
        }
    }

    @Test fun `unknown route and malformed payload end in redacted terminal reconciliation`() {
        val fixture = WarehousePostingFixture(context).also { it.setup() }
        val unknown = UUID.randomUUID(); val malformed = UUID.randomUUID()
        fixture.transaction {
            val piece=receipt(StockQuantity.each("1"))
            post(move(piece,piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN),StockQuantity.each("1")).copy(
                events=listOf(PostingEvent(unknown,WarehouseEventKind.INSPECTED,"secret arbitrary class name"),PostingEvent(malformed,WarehouseEventKind.USE_POSTED,"{"))))
        }
        TenantContext.runAs(fixture.tenant) { repeat(5) { context.getBean(WarehouseOutboxDispatcher::class.java).dispatchOne() } }
        fixture.transaction {
            assertThat(scalar("SELECT state||':'||last_error FROM inventory_outbox_delivery WHERE id='$unknown'")).isEqualTo("TERMINAL:NO_HANDLER")
            assertThat(scalar("SELECT state||':'||last_error FROM inventory_outbox_delivery WHERE id='$malformed'")).isEqualTo("TERMINAL:RECONCILIATION_REQUIRED")
            assertThat(scalar("SELECT count(*) FROM fulfillment_warehouse_observation WHERE id IN ('$unknown','$malformed')")).isEqualTo("0")
        }
    }
}
