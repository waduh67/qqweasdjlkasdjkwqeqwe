package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseFulfillmentITConcurrency : WarehouseFulfillmentFixture() {
    @ParameterizedTest @ValueSource(strings = ["approval", "usage", "plan", "assignment", "authority", "visit-link"])
    fun `competing owner mutation serializes with frozen approval without partial effects`(mutation: String) {
        val case = usageCase()
        used(case)
        val receipt = case.receipt
        val replacement = if (mutation == "assignment") technician(receipt.stock.token).second else null
        val adminId = mapper.readTree(request("GET", "/api/me", receipt.stock.token).contentAsString).path("id").asString()
        val nextPlan = plan(receipt.stock.token, receipt.workOrder, "[${line(receipt.stock.cable)}]", 1)
        completeJob(receipt.workOrder, receipt.receiver.first)
        val before = physicalState(receipt.stock.token)
        val observer = fixture(receipt.stock.token)
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            FulfillmentSqlProbe(context, FulfillmentSqlPhase.SNAPSHOT) {
                reached.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }.use {
                val approval = executor.submit<Int> { request("POST", "/api/work-orders/${receipt.workOrder}/approve", receipt.stock.token, "{}").status }
                check(reached.await(30, TimeUnit.SECONDS))
                val competing = executor.submit<Int> {
                    when (mutation) {
                        "approval" -> request("POST", "/api/work-orders/${receipt.workOrder}/approve", receipt.stock.token, "{}").status
                        "usage" -> use(case).status
                        "plan" -> request("PUT", "/api/work-orders/${receipt.workOrder}/materials/plan", receipt.stock.token, nextPlan).status
                        "assignment" -> request("POST", "/api/work-orders/${receipt.workOrder}/assign", receipt.stock.token, """{"technicianIds":["$replacement"]}""").status
                        "authority" -> request("PUT", "/api/users/$adminId/access", receipt.stock.token, """{"roleIds":[],"areaIds":[]}""").status
                        "visit-link" -> observer.transaction {
                            sql("""INSERT INTO fieldservice_visit(id,tenant_id,order_id,work_order_id,technician_id,state,revision,assignment_active)
                                VALUES ('${java.util.UUID.randomUUID()}','$tenant','${java.util.UUID.randomUUID()}','${receipt.workOrder}',
                                    '${receipt.receiver.second}','PLANNED',0,true)""")
                            200
                        }
                        else -> error("Unknown mutation")
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                var blocked = false
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = observer.transaction { scalar("""SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                        AND usename=current_user AND pid<>pg_backend_pid() AND wait_event_type='Lock'""").toLong() > 0 }
                }
                assertThat(blocked).isTrue()
                release.countDown()
                assertThat(approval.get(45, TimeUnit.SECONDS)).isEqualTo(200)
                assertThat(competing.get(45, TimeUnit.SECONDS)).isEqualTo(if (mutation in setOf("approval", "authority", "visit-link")) 200 else 409)
                observer.transaction {
                    val completed = scalar("SELECT count(*) FROM fulfillment_effect_progress WHERE status='COMPLETED'").toInt()
                    assertThat(completed).isIn(0, 2)
                    assertThat(scalar("SELECT count(*) FROM inventory_material_settlement").toInt()).isEqualTo(completed / 2)
                }
                if (mutation != "authority") assertThat(physicalState(receipt.stock.token)).isEqualTo(before)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test fun `consumer failure rolls back receipt and all effects before durable retry`() {
        val case = FulfillmentSqlProbe(context, FulfillmentSqlPhase.COMPLETED_EFFECT) { error("Injected consumer interruption") }.use { approvedCable() }
        val frozen = frozenRequest(case)
        val before = physicalState(case.receipt.stock.token)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("REQUIRES_RECONCILIATION")
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress")).isEqualTo("0")
        }

        val retried = TenantContext.runAs(frozen.tenantId) { context.getBean(FulfillmentCoordinator::class.java).process(frozen) }

        assertThat(retried.state).isEqualTo(FulfillmentState.APPLIED)
        assertThat(physicalState(case.receipt.stock.token)).isEqualTo(before)
        fixture(case.receipt.stock.token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("1") }
    }

    @Test fun `changed WO between approval and retry prevents every owner effect`() {
        val case = FulfillmentSqlProbe(context, FulfillmentSqlPhase.COMPLETED_EFFECT) { error("Injected consumer interruption") }.use { approvedCable() }
        val frozen = frozenRequest(case)
        fixture(case.receipt.stock.token).transaction { sql("UPDATE work_order SET title='Stale queued context' WHERE id='${case.receipt.workOrder}'") }

        val result = TenantContext.runAs(frozen.tenantId) { context.getBean(FulfillmentCoordinator::class.java).process(frozen) }

        assertThat(result.state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
        fixture(case.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fulfillment_effect_progress")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM workorder_fulfillment_result")).isEqualTo("0")
        }
    }
}
