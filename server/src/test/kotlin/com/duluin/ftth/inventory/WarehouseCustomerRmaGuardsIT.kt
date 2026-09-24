package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseCustomerRmaGuardsIT : WarehouseCustomerRmaFixture() {
    @Test fun `RMA authorization needs acknowledged custody and install rechecks customer and current scopes`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        fun authorize(key: String = "rma-guard-authorization") = request("POST", "/api/work-orders/${case.work}/assets/authorize", case.receipt.receiver.first,
            case.authorization, key)
        val unacknowledged = authorize()
        assertThat(unacknowledged.status).withFailMessage(unacknowledged.contentAsString).isEqualTo(409)
        receiveRma(case, outbound)
        val authorized = authorize()
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val id = mapper.readTree(authorized.contentAsString).path("authorizationId").asString()
        val customer = request("POST", "/api/customers", case.repair.token,
            """{"code":"RMA-UNRELATED","name":"Unrelated customer","address":"Test address","location":{"longitude":106.9,"latitude":-6.2},"areaId":"${area(case.repair.token)}"}""")
        assertThat(customer.status).isEqualTo(201)
        val other = mapper.readTree(customer.contentAsString).path("id").asString()
        val body = """{"authorizationId":"$id","expectedRevision":0,"topology":null}"""
        val wrongCustomer = request("POST", "/api/customers/$other/assets/install", case.receipt.receiver.first, body, "rma-guard-install")
        assertThat(wrongCustomer.status).withFailMessage(wrongCustomer.contentAsString).isEqualTo(409)
        val scope = "/api/v1/warehouse/settings/scopes/${case.receipt.receiver.second}/${case.receipt.field}"
        assertThat(request("PUT", scope, case.repair.token, """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        val revoked = request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first, body, "rma-guard-install")
        assertThat(revoked.status).withFailMessage(revoked.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(revoked.contentAsString).path("code").asString()).isEqualTo("STALE_AUTHORITY")
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT consumed::text FROM inventory_deployment_authorization WHERE id='$id'")).isEqualTo("false")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${case.repair.asset}'")).isEqualTo("1")
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner) FROM inventory_serialized_asset WHERE id='${case.repair.asset}'"))
                .isEqualTo("ISSUED|CUSTOMER")
        }
        assertThat(request("PUT", scope, case.repair.token, """{"expectedRevision":2,"active":true}""").status).isEqualTo(200)
        val stale = request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first, body, "rma-guard-install")
        assertThat(stale.status).withFailMessage(stale.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(stale.contentAsString).path("code").asString()).isEqualTo("STALE_AUTHORITY")
        val renewed = authorize("rma-guard-renewed-authorization")
        assertThat(renewed.status).withFailMessage(renewed.contentAsString).isEqualTo(200)
        val renewedBody = body.replace(id, mapper.readTree(renewed.contentAsString).path("authorizationId").asString())
        val installed = request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first, renewedBody, "rma-guard-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        assertThat(request("PUT", scope, case.repair.token, """{"expectedRevision":3,"active":false}""").status).isEqualTo(200)
        val replay = request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first, renewedBody, "rma-guard-install")
        assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(replay.contentAsString).path("code").asString()).isEqualTo("STALE_AUTHORITY")
    }

    @Test fun `simultaneous RMA installs return one durable physical installation`() {
        val case = prepareRma()
        receiveRma(case, dispatchRma(case))
        val authorized = request("POST", "/api/work-orders/${case.work}/assets/authorize", case.receipt.receiver.first,
            case.authorization, "rma-race-authorization")
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val permit = mapper.readTree(authorized.contentAsString)
        val id = permit.path("authorizationId").asString()
        val operation = permit.path("operationId").asString()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val responses = Executors.newFixedThreadPool(2).use { pool ->
            val futures = List(2) {
                pool.submit<org.springframework.mock.web.MockHttpServletResponse> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    request("POST", "/api/customers/${case.customer}/assets/install", case.receipt.receiver.first,
                        """{"authorizationId":"$id","expectedRevision":0,"topology":null}""", "rma-race-install")
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        }
        assertThat(responses.map { it.status }).withFailMessage(responses.joinToString("\n") { it.contentAsString }).containsExactly(201, 201)
        assertThat(responses.map { it.contentAsString }.distinct()).hasSize(1)
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE operation_id='$operation'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${case.repair.asset}' AND ended_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='${case.repair.asset}'")).isEqualTo("2")
        }
    }

    @Test fun `RMA rejects a substituted serial and non-repair work order before dispatch`() {
        val case = prepareRma()
        val wrongWork = workOrder(case.repair.token, "PSB", case.customer.toString())
        assign(case.repair.token, wrongWork, case.receipt.receiver.second)
        for ((suffix, body) in listOf("serial" to case.body.replace(case.repair.serial, "UNRELATED-SERIAL"),
            "work" to case.body.replace(case.work, wrongWork))) {
            val result = request("POST", "${case.repair.path}/rma-handover", case.repair.token, body, "invalid-rma-$suffix")
            assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
        }
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_rma_handover")).isEqualTo("0")
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner) FROM inventory_serialized_asset WHERE id='${case.repair.asset}'"))
                .isEqualTo("QUARANTINE|CUSTOMER")
        }
        dispatchRma(case)
    }

    @Test fun `RMA acknowledgement and read replay require current location scope`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        receiveRma(case, outbound)
        val path = "/api/v1/warehouse/rma-handovers/${outbound.path("id").asString()}"
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receipt.receiver.second}/${case.receipt.field}", case.repair.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(request("GET", path, case.receipt.receiver.first).status).isEqualTo(404)
        assertThat(request("POST", "$path/acknowledge", case.receipt.receiver.first, case.ack, "rma-ack").status).isEqualTo(404)
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='${outbound.path("id").asString()}'")).isEqualTo("2")
        }
    }

    @Test fun `app role cannot rewrite RMA source or acknowledge without custody posting`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        val id = outbound.path("id").asString()
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                for (mutation in listOf("UPDATE inventory_rma_handover SET body='{}' WHERE id='$id'",
                    """INSERT INTO inventory_rma_receipt(tenant_id,handover_id,actor_id,request)
                        VALUES ('$tenant','$id','${case.receipt.receiver.second}','${case.ack}'::jsonb)""")) {
                    val point = connection.setSavepoint()
                    val failure = runCatching { connection.createStatement().use { statement ->
                        statement.execute(mutation)
                        statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    } }.exceptionOrNull()
                    connection.rollback(point)
                    assertThat(failure).isInstanceOf(SQLException::class.java)
                    assertThat((failure as SQLException).sqlState).isEqualTo("23514")
                }
            }
            assertThat(scalar("SELECT count(*) FROM inventory_rma_receipt")).isEqualTo("0")
            assertThat(scalar("SELECT state FROM inventory_document WHERE id='$id'")).isEqualTo("DISPATCHED")
        }
        receiveRma(case, outbound)
    }
}
