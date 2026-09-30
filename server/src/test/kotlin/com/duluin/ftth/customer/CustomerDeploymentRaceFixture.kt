package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class CustomerDeploymentRaceFixture : CustomerDeploymentFixture() {
    @ParameterizedTest
    @ValueSource(strings = ["REVOKED", "SCOPE", "REASSIGNED", "CANCELLED", "CUSTOMER", "REVISION", "MINT_REVOKED", "MINT_SCOPE"])
    fun `consume waiting on authority or WO lock observes the committed change`(change: String) {
        val install = installation()
        val stock = fixture(install.receipt.stock.token)
        val mutation = change.removePrefix("MINT_")
        Executors.newSingleThreadExecutor().use { executor ->
            lateinit var future: java.util.concurrent.Future<Int>
            stock.transaction {
                val authorityChange = mutation in setOf("REVOKED", "SCOPE")
                sql(if (authorityChange) "SELECT epoch FROM iam_authorization_epoch WHERE tenant_id='$tenant' FOR UPDATE"
                    else "SELECT id FROM work_order WHERE tenant_id='$tenant' AND id='${install.receipt.workOrder}' FOR UPDATE")
                val holder = scalar("SELECT pg_backend_pid()").toInt()
                future = executor.submit<Int> {
                    if (change.startsWith("MINT_")) {
                        val selected = install.receipt.input.lines.single()
                        request("POST", "/api/work-orders/${install.receipt.workOrder}/assets/authorize", install.receipt.receiver.first,
                            """{"expectedRevision":${install.receipt.input.workOrderRevision},"assetId":"${selected.stockIdentityId}","issueLineId":"${selected.issueLineId}","purpose":"INSTALL"}""",
                            "mint-race").status
                    } else consume(install).status
                }
                jdbc { awaitBlocked(it, holder) }
                when (mutation) {
                    "REVOKED" -> sql("UPDATE app_user SET status='DISABLED' WHERE id='${install.receipt.receiver.second}'")
                    "SCOPE" -> sql("DELETE FROM user_area WHERE user_id='${install.receipt.receiver.second}'")
                    "REASSIGNED" -> sql("DELETE FROM work_order_assignee WHERE work_order_id='${install.receipt.workOrder}'")
                    "CANCELLED" -> sql("UPDATE work_order SET status='CANCELLED' WHERE id='${install.receipt.workOrder}'")
                    "CUSTOMER" -> sql("UPDATE work_order SET customer_id=NULL WHERE id='${install.receipt.workOrder}'")
                    "REVISION" -> sql("UPDATE work_order SET warehouse_revision=warehouse_revision+1 WHERE id='${install.receipt.workOrder}'")
                    else -> error("Unknown change")
                }
                if (authorityChange) sql("UPDATE iam_authorization_epoch SET epoch=epoch+1,revision=revision+1 WHERE tenant_id='$tenant'")
            }
            assertThat(future.get(40, TimeUnit.SECONDS)).isIn(401, 403, 409)
        }
        assertUninstalled(install)
    }

    @Test
    fun `concurrent different consume keys commit one physical installation`() {
        val install = installation()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val results = (1..2).map { number -> executor.submit<Int> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                consume(install, "race-$number").status
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            assertThat(results.map { it.get(40, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(201, 409)
        }
        fixture(install.receipt.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='${install.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE id='${install.operation}'")).isEqualTo("1")
        }
    }

    @Test
    fun `customer insert constraint failure rolls back posting assignment and consumption`() {
        val install = installation()
        val tenant = fixture(install.receipt.stock.token).tenant
        check(System.getenv("WAREHOUSE_QA") == "true")
        val url = requireNotNull(System.getenv("SPRING_DATASOURCE_URL"))
        check(url == "jdbc:postgresql://127.0.0.1:25432/warehouse_test")
        DriverManager.getConnection(url, System.getenv("SPRING_FLYWAY_USER"), System.getenv("SPRING_FLYWAY_PASSWORD")).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL app.tenant_id='$tenant'")
                statement.execute("""INSERT INTO onu(id,tenant_id,customer_id,serial_number,warehouse_admission)
                    VALUES ('${install.operation}','$tenant','${install.customer}','COLLISION-${install.operation}','LEGACY_UNRESOLVED')""")
            }
            connection.commit()
        }

        val response = consume(install)

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        assertUninstalled(install)
    }

    private fun awaitBlocked(connection: Connection, holder: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        connection.prepareStatement("SELECT EXISTS(SELECT FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))").use { query ->
            query.setInt(1, holder)
            while (System.nanoTime() < deadline) {
                val blocked = query.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
                if (blocked) return
                Thread.onSpinWait()
            }
        }
        error("Consume did not wait on the held database fence")
    }
}
