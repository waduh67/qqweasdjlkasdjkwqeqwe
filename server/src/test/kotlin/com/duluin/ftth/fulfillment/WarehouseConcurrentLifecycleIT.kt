package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.ReceiptRealStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@Import(ReceiptRealStorage::class, WarehouseLifecycleFailureConfiguration::class)
class WarehouseConcurrentLifecycleIT : WarehouseNumericLifecycleFixture() {
    @Autowired private lateinit var failure: WarehouseLifecycleFailurePort

    @Test fun `complete physical device and return fixture preserves exact warehouse and customer totals through concurrent use and QA`() {
        val case = numericCase()
        val used = concurrently { numericUse(case) }
        assertThat(used[0]).isEqualTo(used[1])
        val usage = used.first()
        val installed = numericInstall(case)
        val signature = numericHandover(case, installed)
        assertDeploymentFence(case, installed)
        val returned = numericReturn(case, usage)
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
        val before = numericAudit(case)
        concurrently { saveCommand(returned.inspection.path, returned.inspection.token, returned.inspection.body,
            returned.inspection.key) }.forEach { assertThat(it).isEqualTo(returned.inspection) }
        numericComplete(case, signature)
        for (fault in listOf("OMITTED", "FOREIGN_ASSET", "FOREIGN_ACTOR", "OMIT_USAGE")) {
            val guard = if (fault == "OMIT_USAGE") "deployment-only settlement requires nonempty serialized material sources"
                else "fulfillment deployment witnesses do not match posted installations"
            failure.tamperDeployment.set(fault)
            failure.lastFailure.set(null)
            try {
                assertThatThrownBy {
                    request("POST", "/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "numeric-invalid-witness")
                }.hasRootCauseInstanceOf(java.sql.SQLException::class.java)
                    .hasStackTraceContaining(guard)
                assertThat(failure.lastFailure.get()).contains(guard)
            } finally { failure.tamperDeployment.set(null) }
            assertThat(numericAudit(case)).isEqualTo(before)
            fixture(case.stock.token).transaction {
                assertThat(scalar("SELECT count(*) FROM fulfillment_approval_snapshot")).isEqualTo("0")
                assertThat(scalar("SELECT approval_status FROM work_order WHERE id='${case.workOrder}'")).isEqualTo("PENDING")
            }
        }
        saveCommand("/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "numeric-approve")
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
        assertThat(numericAudit(case)).isEqualTo(before)
        fixture(case.stock.token).transaction {
            val frozen = mapper.readTree(scalar("SELECT snapshot FROM fulfillment_approval_snapshot WHERE work_order_id='${case.workOrder}'"))
            val witnessed = frozen.path("material").path("deployments").single()
            assertThat(witnessed.path("assetId").asString()).isEqualTo(mapper.readTree(installed.original).path("assetId").asString())
            assertThat(witnessed.path("assignmentId").asString()).isEqualTo(mapper.readTree(installed.original).path("assignmentId").asString())
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement WHERE result='VERIFIED'")).isEqualTo("1")
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint WHERE work_order_id='${case.workOrder}'")).isEqualTo("APPLIED")
        }
        assertThat(numericAudit(case)).isEqualTo(before)
    }

    @Test fun `failure after real usage owner returns rolls back its movement cut facts and operation before exact retry`() {
        val case = numericCase()
        val before = numericAudit(case)
        val totals = numericTotals(case)
        failure.reached.set(false)
        failure.rejectUsage.set(true)
        try {
            val response = request("POST", "/api/work-orders/${case.workOrder}/materials/report-use", case.technician.first,
                mapper.writeValueAsString(case.usageInput), "numeric-use")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
            assertThat(failure.reached.get()).isTrue()
        } finally { failure.rejectUsage.set(false) }
        assertThat(numericAudit(case)).isEqualTo(before)
        assertThat(numericTotals(case)).isEqualTo(totals)
        val usage = numericUse(case)
        val installed = numericInstall(case)
        val signature = numericHandover(case, installed)
        numericReturn(case, usage)
        numericComplete(case, signature)
        saveCommand("/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "numeric-approve")
        assertThat(numericTotals(case)).isEqualTo("917500|82500|0|9|1|1|10|1")
    }

    @Test fun `cable usage and signed completion cannot stand in for an uninstalled planned ONU`() {
        val case = numericCase()
        numericUse(case)
        numericComplete(case, numericSignature(case))
        val before = numericAudit(case)
        val response = request("POST", "/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "missing-installation")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertThat(numericAudit(case)).isEqualTo(before)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM fulfillment_approval_snapshot")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("0")
            assertThat(scalar("SELECT approval_status FROM work_order WHERE id='${case.workOrder}'")).isEqualTo("PENDING")
        }
    }

    private fun <T> concurrently(action: () -> T): List<T> {
        val barrier = CyclicBarrier(2)
        return Executors.newFixedThreadPool(2).use { pool ->
            (1..2).map { pool.submit<T> { barrier.await(20, TimeUnit.SECONDS); action() } }.map { it.get(45, TimeUnit.SECONDS) }
        }
    }

    private fun assertDeploymentFence(case: NumericCase, installed: SavedCommand) {
        val assignment = mapper.readTree(installed.original).path("assignmentId").asString()
        val workerPid = AtomicInteger()
        val started = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { pool ->
            lateinit var waiting: java.util.concurrent.Future<String>
            fixture(case.stock.token).transaction {
                val holder = scalar("SELECT pg_backend_pid()")
                sql("""SELECT warehouse_material_deployment_sources('$tenant','${case.workOrder}',
                    ARRAY(SELECT id FROM inventory_material_plan WHERE work_order_id='${case.workOrder}'))""")
                waiting = pool.submit<String> { fixture(case.stock.token).transaction {
                    workerPid.set(scalar("SELECT pg_backend_pid()").toInt()); started.countDown()
                    scalar("SELECT id FROM inventory_asset_assignment WHERE id='$assignment' FOR UPDATE")
                } }
                check(started.await(15, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (scalar("SELECT $holder=ANY(pg_blocking_pids(${workerPid.get()}))") != "t") {
                    check(System.nanoTime() < deadline) { "Installation witness did not hold its assignment lock" }
                    Thread.onSpinWait()
                }
                assertThat(waiting.isDone).isFalse()
            }
            assertThat(waiting.get(20, TimeUnit.SECONDS)).isEqualTo(assignment)
        }
    }
}
