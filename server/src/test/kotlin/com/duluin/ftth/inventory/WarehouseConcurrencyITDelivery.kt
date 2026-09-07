package com.duluin.ftth.inventory

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WarehouseConcurrencyITDelivery {
    private lateinit var database: WarehouseSchemaDatabase
    private lateinit var context: org.springframework.context.ConfigurableApplicationContext
    @BeforeAll fun start() { database = WarehouseSchemaDatabase(); context = postingContext(database) }
    @AfterAll fun stop() { context.close(); database.close() }
    private fun fixture() = WarehousePostingFixture(context).also { fixture ->
        fixture.setup(); fixture.transaction { receipt(StockQuantity.each("1")) }
    }
    private fun store() = context.getBean(WarehouseOutboxDeliveryStore::class.java)
    private fun claim(fixture: WarehousePostingFixture, owner: UUID = UUID.randomUUID()) = TenantContext.runAs(fixture.tenant) { store().claim(owner) }

    @Test fun `multiple nodes get one active lease and cannot spoof completion`() {
        val fixture = fixture()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map { pool.submit<WarehouseDeliveryLease?> { check(start.await(10, TimeUnit.SECONDS)); claim(fixture) } }
            start.countDown()
            val leases = futures.mapNotNull { it.get(20, TimeUnit.SECONDS) }
            assertThat(leases).hasSize(1)
            val lease = leases.single()
            TenantContext.runAs(fixture.tenant) {
                assertThat(store().delivered(lease.copy(owner = UUID.randomUUID()))).isFalse()
                assertThat(store().delivered(lease.copy(token = UUID.randomUUID()))).isFalse()
                assertThat(store().delivered(lease)).isTrue()
            }
            assertThat(claim(fixture)).isNull()
        } finally { pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS) }
    }

    @Test fun `expired lease changes token and rejects old owner even on same node`() {
        val fixture = fixture()
        val original = requireNotNull(claim(fixture))
        fixture.transaction { sql("UPDATE inventory_outbox_delivery SET lease_until=clock_timestamp()-interval '1 microsecond',revision=revision+1 WHERE id='${original.eventId}'") }
        assertThat(TenantContext.runAs(fixture.tenant) { store().delivered(original) }).isFalse()
        val takeover = requireNotNull(claim(fixture, original.owner))
        assertThat(takeover.token).isNotEqualTo(original.token)
        assertThat(takeover.attempt).isEqualTo(2)
        TenantContext.runAs(fixture.tenant) {
            assertThat(store().delivered(original)).isFalse()
            assertThat(store().delivered(takeover)).isTrue()
        }
    }

    @Test fun `retry is delayed bounded and terminal state queryable`() {
        val fixture = fixture()
        repeat(8) { attempt ->
            val lease = requireNotNull(claim(fixture))
            assertThat(lease.attempt).isEqualTo(attempt + 1)
            assertThat(TenantContext.runAs(fixture.tenant) { store().failed(lease, WarehouseDeliveryFailure.RETRYABLE) }).isTrue()
            assertThat(claim(fixture)).isNull()
            fixture.transaction {
                assertThat(scalar("SELECT extract(epoch FROM next_attempt_at-clock_timestamp()) BETWEEN 0 AND 300 FROM inventory_outbox_delivery")).isEqualTo("t")
                sql("UPDATE inventory_outbox_delivery SET next_attempt_at=clock_timestamp(),revision=revision+1 WHERE id='${lease.eventId}'")
            }
        }
        fixture.transaction {
            assertThat(scalar("SELECT state FROM inventory_outbox_delivery")).isEqualTo("TERMINAL")
            assertThat(scalar("SELECT attempts FROM inventory_outbox_delivery")).isEqualTo("8")
            assertThat(scalar("SELECT last_error FROM inventory_outbox_delivery")).isEqualTo("RETRYABLE")
        }
        assertThat(claim(fixture)).isNull()
    }

    @Test fun `inbox and owner effect roll back together then redelivery applies once`() {
        val fixture = fixture()
        val event = fixture.transaction { UUID.fromString(scalar("SELECT id FROM inventory_outbox")) }
        val inbox = context.getBean(WarehouseInboxApi::class.java)
        val before = fixture.transaction { scalar("SELECT revision FROM inventory_location WHERE id='$warehouse'") }
        assertThatThrownBy { fixture.transaction {
            inbox.consume(event, "warehouse.test") {
                sql("UPDATE inventory_location SET name='effect',revision=revision+1 WHERE id='$warehouse'")
                error("injected after owner effect")
            }
        } }.isInstanceOf(IllegalStateException::class.java)
        fixture.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("0")
            assertThat(scalar("SELECT revision FROM inventory_location WHERE id='$warehouse'")).isEqualTo(before)
            assertThat(inbox.consume(event, "warehouse.test") { sql("UPDATE inventory_location SET name='effect',revision=revision+1 WHERE id='$warehouse'") }).isTrue()
        }
        fixture.transaction {
            assertThat(inbox.consume(event, "warehouse.test") { error("duplicate applied") }).isFalse()
            assertThat(scalar("SELECT count(*) FROM inventory_inbox")).isEqualTo("1")
            assertThat(scalar("SELECT revision FROM inventory_location WHERE id='$warehouse'").toLong()).isEqualTo(before.toLong() + 1)
        }
    }

    @Test fun `fresh Spring context retains inbox and expired lease recovers after restart`() {
        val fixture = fixture()
        val lease = requireNotNull(claim(fixture))
        fixture.transaction {
            context.getBean(WarehouseInboxApi::class.java).consume(lease.eventId, "warehouse.restart") {
                sql("UPDATE inventory_location SET name='durable-effect',revision=revision+1 WHERE id='$warehouse'")
            }
            sql("UPDATE inventory_outbox_delivery SET lease_until=clock_timestamp()-interval '1 second',revision=revision+1 WHERE id='${lease.eventId}'")
        }
        context.close()
        context = postingContext(database)
        val restarted = WarehousePostingFixture(context, fixture.tenant)
        restarted.transaction {
            assertThat(context.getBean(WarehouseInboxApi::class.java).consume(lease.eventId, "warehouse.restart") { error("reapplied after restart") }).isFalse()
            assertThat(scalar("SELECT name FROM inventory_location WHERE id='${fixture.warehouse}'")).isEqualTo("durable-effect")
        }
        val next = requireNotNull(claim(restarted))
        assertThat(next.attempt).isEqualTo(2)
        assertThat(TenantContext.runAs(restarted.tenant) { store().delivered(next) }).isTrue()
    }
}
