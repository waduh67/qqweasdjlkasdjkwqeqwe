package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.ReceiptRealStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

@Import(ReceiptRealStorage::class)
class WarehouseSerialSettlementIT : WarehouseNumericLifecycleFixture() {
    @Test fun `serialized-only job verifies the actual installation without inventing bulk usage`() {
        val case = numericCase(includeCable = false)
        val field = request("GET", "/api/work-orders/${case.workOrder}/materials/workbench", case.technician.first)
        assertThat(field.status).isEqualTo(200)
        assertThat(mapper.readTree(field.contentAsString).path("hasMeasuredMaterials").asBoolean()).isFalse()
        val installed = numericInstall(case)
        val signature = numericHandover(case, installed)
        numericComplete(case, signature)
        val before = numericAudit(case)
        saveCommand("/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "serial-only-approve")
        assertThat(numericAudit(case)).isEqualTo(before)
        assertThat(numericTotals(case)).isEqualTo("1000000|0|0|9|1|1|10|1")
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_usage_snapshot")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_material_usage")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_customer_material_fact")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='USAGE'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement WHERE usage_id IS NULL AND result='VERIFIED' AND use_revision=1")).isEqualTo("1")
            val frozen = mapper.readTree(scalar("SELECT snapshot FROM fulfillment_approval_snapshot"))
            assertThat(frozen.path("material").path("usageId").isNull).isTrue()
            assertThat(frozen.path("material").path("usageBody").isNull).isTrue()
            assertThat(frozen.path("material").path("usageHash").isNull).isTrue()
            assertThat(frozen.path("material").path("deployments").size()).isEqualTo(1)
            assertThat(scalar("SELECT state FROM fulfillment_checkpoint")).isEqualTo("APPLIED")
        }
        val review = request("GET", "/api/work-orders/${case.workOrder}/materials/workbench/approval-review", case.stock.token)
        assertThat(review.status).withFailMessage(review.contentAsString).isEqualTo(200)
        val body = mapper.readTree(review.contentAsString).path("review")
        assertThat(body.path("usage").isNull).isTrue()
        val deployment = body.path("deployments").single()
        assertThat(deployment.path("assetId").asString()).isEqualTo(mapper.readTree(installed.original).path("assetId").asString())
        assertThat(deployment.path("sku").path("name").asString()).isEqualTo("ONU")
        assertThat(deployment.path("serial").asString()).startsWith("NUMERIC-ONU-")
        assertThat(review.contentAsString).doesNotContain("cost", "payload", "sessionId", "subscription", "proofHash", "email")
        assertThat(numericAudit(case)).isEqualTo(before)
        val path = "/api/work-orders/${case.workOrder}/materials/workbench/approval-review"
        assertThat(request("GET", path, case.technician.first).status).isEqualTo(200)
        assertThat(request("GET", path, tenant()).status).isEqualTo(404)
        val removalOrder = workOrder(case.stock.token, "DISMANTLE", case.customer)
        assign(case.stock.token, removalOrder, case.technician.second)
        assertThat(request("POST", "/api/work-orders/$removalOrder/start", case.technician.first).status).isEqualTo(200)
        val removalSignature = numericSignature(case.copy(workOrder = removalOrder))
        saveCommand("/api/customers/${case.customer}/assets/remove", case.technician.first,
            mapper.writeValueAsString(mapOf("assignmentId" to deployment.path("assignmentId").asString(),
                "expectedRevision" to 1, "expectedTitleRevision" to 0, "workOrderId" to removalOrder,
                "evidenceId" to removalSignature)), "serial-only-remove")
        val afterRemoval = numericAudit(case)
        val historical = request("GET", path, case.stock.token)
        assertThat(historical.status).withFailMessage(historical.contentAsString).isEqualTo(200)
        assertThat(historical.contentAsString).isEqualTo(review.contentAsString)
        assertThat(numericAudit(case)).isEqualTo(afterRemoval)
        val location = case.receipt.path("lines").single().path("accepted").path("locationId").asString()
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.technician.second}/$location", case.stock.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        val hidden = request("GET", path, case.technician.first)
        assertThat(hidden.status).isEqualTo(404)
        assertThat(hidden.contentAsString).doesNotContain(deployment.path("serial").asString())
        assertThat(numericTotals(case)).isEqualTo("1000000|0|0|9|0|0|10|1")
    }

    @Test fun `serialized-only signed completion without its installation remains unverified`() {
        val case = numericCase(includeCable = false)
        numericComplete(case, numericSignature(case))
        val before = numericAudit(case)
        val denied = request("POST", "/api/work-orders/${case.workOrder}/approve", case.stock.token, "{}", "serial-missing")
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(409)
        assertThat(numericAudit(case)).isEqualTo(before)
        fixture(case.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM fulfillment_approval_snapshot")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_material_settlement")).isEqualTo("0")
            assertThat(scalar("SELECT approval_status FROM work_order")).isEqualTo("PENDING")
        }
    }
}
