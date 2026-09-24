package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseReturnIT : MaterialLifecycleFixture() {
    private fun intakeBody(case: ResidualCase, residual: String) =
        """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$residual","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"warehouse-return-receipt"}"""

    private fun intake(case: ResidualCase, residual: String): String {
        val response = request("POST", "/api/v1/warehouse/returns", case.usage.receipt.stock.token, intakeBody(case, residual))
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        return mapper.readTree(response.contentAsString).path("id").asString()
    }

    @Test fun `unacknowledged foreign and wrong return origins cannot create inspection stock`() {
        val case = residualCase()
        val token = case.usage.receipt.stock.token
        val residual = dispatchedResidual(case)
        val body = intakeBody(case, residual)
        assertThat(request("POST", "/api/v1/warehouse/returns", token, body).status).isEqualTo(409)
        assertThat(acknowledgeResidual(case, residual).status).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/returns", tenant(), body).status).isEqualTo(404)
        assertThat(request("POST", "/api/v1/warehouse/returns", token,
            body.replace(residual, UUID.randomUUID().toString())).status).isEqualTo(404)
        assertThat(request("POST", "/api/v1/warehouse/returns", case.usage.receipt.receiver.first, body).status).isEqualTo(403)
        fixture(token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_return_case")).isEqualTo("0")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("900000")
        }
    }

    @Test fun `overmeasurement and damaged available destination are rejected before any posting`() {
        val case = residualCase()
        val token = case.usage.receipt.stock.token
        val residual = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, residual).status).isEqualTo(200)
        val id = intake(case, residual)
        val body = """{"expectedRevision":0,"measuredQuantityBase":"17501","condition":"SERVICEABLE","destinationLocationId":"${case.input.targetLocationId}","evidenceReference":"measurement","resetConfirmed":false}"""
        val before = fixture(token).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        assertThat(request("POST", "/api/v1/warehouse/returns/$id/inspect", token, body).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/returns/$id/inspect", token,
            body.replace("17501", "17500")).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/returns/$id/inspect", token,
            body.replace("17501", "17.5")).status).isEqualTo(400)
        fixture(token).transaction { assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before) }
        val held = request("POST", "/api/v1/warehouse/returns/$id/inspect", token,
            body.replace("17501", "17500").replace("SERVICEABLE", "DAMAGED"))
        assertThat(held.status).withFailMessage(held.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(held.contentAsString).path("state").asString()).isEqualTo("RECEIVED_IN_INSPECTION")
    }

    @Test fun `concurrent inspection cannot post the same return twice`() {
        val case = residualCase()
        val token = case.usage.receipt.stock.token
        val residual = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, residual).status).isEqualTo(200)
        val id = intake(case, residual)
        val body = """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"DAMAGED","destinationLocationId":"${case.input.targetLocationId}","evidenceReference":"damaged-jacket","resetConfirmed":false}"""
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val result = (1..2).map { pool.submit(Callable {
                barrier.await(20, TimeUnit.SECONDS)
                request("POST", "/api/v1/warehouse/returns/$id/inspect", token, body).status
            }) }.map { it.get(60, TimeUnit.SECONDS) }
            assertThat(result.sorted()).containsExactly(200, 409)
        } finally { pool.shutdownNow() }
        fixture(token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("1")
        }
        val history = request("GET", "/api/v1/warehouse/returns/$id/history", token)
        assertThat(history.status).isEqualTo(200)
        assertThat(mapper.readTree(history.contentAsString).size()).isEqualTo(2)
    }

    @Test fun `returned seventeen point five metre remnant becomes available only after measured inspection`() {
        val case = residualCase()
        val token = case.usage.receipt.stock.token
        val residual = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, residual).status).isEqualTo(200)
        val warehouse = create("locations", token,
            """{"code":"INSPECTED_WH","name":"Inspected returns","kind":"WAREHOUSE"}""").path("id").asString()
        val bin = create("locations", token,
            """{"code":"INSPECTED_BIN","name":"Reusable remnants","kind":"BIN","parentLocationId":"$warehouse","issueEligible":true}""").path("id").asString()
        val intake = request("POST", "/api/v1/warehouse/returns", token,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$residual","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"warehouse-return-receipt"}""", "return-intake")
        assertThat(intake.status).withFailMessage(intake.contentAsString).isEqualTo(201)
        val record = mapper.readTree(intake.contentAsString)
        assertThat(record.path("stockIdentityId").asString()).isEqualTo(case.input.stockIdentityId.toString())
        assertThat(record.path("quantityBase").asString()).isEqualTo("17500")
        fixture(token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("900000")
        }
        val id = record.path("id").asString()
        val input = """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"SERVICEABLE","destinationLocationId":"$bin","evidenceReference":"measured-17.500m-intact-jacket","resetConfirmed":false}"""
        val inspected = request("POST", "/api/v1/warehouse/returns/$id/inspect", token, input, "return-inspect")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(inspected.contentAsString).path("state").asString()).isEqualTo("ACCEPTED")
        fixture(token).transaction {
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("917500")
            assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='CONSUMED'")).isEqualTo("82500")
            assertThat(scalar("SELECT parent_segment_id IS NOT NULL FROM inventory_segment WHERE id='${case.input.stockIdentityId}'")).isEqualTo("t")
        }
        assertThat(request("POST", "/api/v1/warehouse/returns/$id/inspect", token, input, "return-inspect").contentAsString)
            .isEqualTo(inspected.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/returns/$id/inspect", token,
            input.replace("17500", "17501"), "return-overmeasure").status).isEqualTo(409)
    }
}
