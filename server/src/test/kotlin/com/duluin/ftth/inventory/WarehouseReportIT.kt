package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WarehouseReportIT : MaterialLifecycleFixture() {
    override fun stockReceiptCost() = mapOf("totalMinor" to "1000006", "currency" to "IDR")

    private fun report(token: String, path: String) = request("GET", "/api/v1/warehouse/reports/$path", token).let {
        assertThat(it.status).withFailMessage(it.contentAsString).isEqualTo(200)
        mapper.readTree(it.contentAsString)
    }

    @Test fun `returned remnant stock card preserves opening balance and exact original use cost`() {
        val case = residualCase()
        val stock = case.usage.receipt.stock
        val token = stock.token
        val residual = dispatchedResidual(case)
        assertThat(acknowledgeResidual(case, residual).status).isEqualTo(200)
        val intake = request("POST", "/api/v1/warehouse/returns", token,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"$residual","quarantineLocationId":"${case.input.targetLocationId}","evidenceReference":"return-slip"}""")
        assertThat(intake.status).withFailMessage(intake.contentAsString).isEqualTo(201)
        val returned = mapper.readTree(intake.contentAsString).path("id").asString()
        val inspected = request("POST", "/api/v1/warehouse/returns/$returned/inspect", token,
            """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"SERVICEABLE","destinationLocationId":"${stock.bin}","evidenceReference":"measured-return","resetConfirmed":false}""")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        val before = fixture(token).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        val stockReport = report(token, "stock?skuId=${stock.cable}").path("items").single()
        assertThat(stockReport.path("available").path("quantityBase").asString()).isEqualTo("917500")
        assertThat(stockReport.path("statusBuckets").path("CONSUMED").asString()).isEqualTo("82500")
        assertThat(report(token, "custody-aging").path("totalElements").asInt()).isZero()
        assertThat(report(token, "transit-backlog").path("totalElements").asInt()).isZero()
        val card = report(token, "stock-card?skuId=${stock.cable}&locationId=${stock.bin}")
        val last = card.path("items").last()
        assertThat(last.path("closingQuantityBase").asString()).isEqualTo("917500")
        val from = Instant.parse(last.path("recordedAt").asString())
        val range = report(token, "stock-card?skuId=${stock.cable}&locationId=${stock.bin}&from=$from&until=${from.plusSeconds(10)}")
        assertThat(range.path("items").first().path("openingQuantityBase").asString()).isEqualTo("900000")
        val costs = report(token, "work-order-costs")
        val cost = costs.path("items").single()
        assertThat(cost.path("quantityBase").asString()).isEqualTo("82500")
        assertThat(cost.path("sourceTotalMinor").asString()).isEqualTo("1000006")
        assertThat(cost.path("sourceBasisQuantityBase").asString()).isEqualTo("1000000")
        assertThat(cost.path("lineTotalMinor").asString()).isEqualTo("82500")
        assertThat(costs.path("currencyTotals").single().path("currency").asString()).isEqualTo("IDR")
        assertThat(costs.path("currencyTotals").single().path("totalMinor").asString()).isEqualTo("82500")
        assertThat(costs.path("unknownQuantities").size()).isZero()
        val receipt = fixture(token).transaction { scalar("SELECT id FROM inventory_document WHERE kind='RECEIPT'") }
        val issue = fixture(token).transaction { scalar("SELECT id FROM inventory_document WHERE kind='ISSUE'") }
        val prints = listOf("$receipt/revisions/1", "$issue/revisions/2", "$returned/revisions/0").associateWith {
            report(token, "documents/$it/print").toString()
        }
        assertThat(request("PUT", "/api/v1/warehouse/skus/${stock.cable}", token,
            """{"expectedRevision":1,"code":"CABLE","name":"=HYPERLINK(\"example\")","tracking":"LOT","baseUnit":"MM","inspectionRequired":false}""").status).isEqualTo(200)
        prints.forEach { (path, body) -> assertThat(report(token, "documents/$path/print").toString()).isEqualTo(body) }
        assertThat(report(token, "work-order-costs").path("items").single().path("lineTotalMinor").asString()).isEqualTo("82500")
        val csv = request("GET", "/api/v1/warehouse/reports/stock/export.csv", token)
        assertThat(csv.status).isEqualTo(200)
        assertThat(csv.contentAsString).contains("\"'=HYPERLINK(\"\"example\"\")\"")
        assertThat(fixture(token).transaction { scalar("SELECT count(*) FROM inventory_movement") })
            .isEqualTo(before)
    }

    @Test fun `unknown receipt cost stays unknown and never creates a zero monetary total`() {
        val case = usageCase(fungible = true)
        used(case)
        val costs = report(case.receipt.stock.token, "work-order-costs")
        assertThat(costs.path("items").single().path("costState").asString()).isEqualTo("UNKNOWN")
        assertThat(costs.path("items").single().path("lineTotalMinor").isNull).isTrue()
        assertThat(costs.path("currencyTotals").size()).isZero()
        assertThat(costs.path("unknownQuantities").single().path("quantityBase").asString()).isEqualTo("82")
    }

    @Test fun `report access does not require stock view and hides costs foreign records and unscoped pages`() {
        val setup = setupReceipt()
        val receipt = draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"REPORT-PRIVATE"}],"cost":{"totalMinor":"999991","currency":"IDR"}}""")
        transition(setup, receipt.path("id").asString(), "receive", """{"expectedRevision":0}""")
        val asset = fixture(setup.token).transaction { scalar("SELECT id FROM inventory_serialized_asset WHERE warehouse_admission='VERIFIED'") }
        val (viewer, viewerId) = user(setup.token, setOf("inventory.report.view"))
        val role = mapper.readTree(request("GET", "/api/me", viewer).contentAsString).path("roleIds")[0].asString()
        assertThat(request("PUT", "/api/users/$viewerId/access", setup.token,
            """{"roleIds":["$role"],"areaIds":["${area(setup.token)}"]}""").status).isEqualTo(200)
        for (location in listOf(setup.source, setup.inspection)) assertThat(request("PUT",
            "/api/v1/warehouse/settings/scopes/$viewerId/$location", setup.token, """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        assertThat(request("GET", "/api/v1/warehouse/stock", viewer).status).isEqualTo(403)
        val paths = listOf("stock", "stock-card", "custody-aging", "transit-backlog", "loan-assets", "sold-assets", "movements",
            "serial-chain/$asset", "documents/${receipt.path("id").asString()}/revisions/1/print")
        paths.forEach { path ->
            val result = report(viewer, path).toString()
            assertThat(result).doesNotContain("cost", "Minor", "currency", "999991", "customerLabel", "evidence", "objectKey", "authorityEpoch", "session")
        }
        assertThat(request("GET", "/api/v1/warehouse/reports/work-order-costs", viewer).status).isEqualTo(403)
        assertThat(request("GET", "/api/v1/warehouse/reports/work-order-costs/export.csv", viewer).status).isEqualTo(403)
        val foreign = tenant()
        assertThat(report(foreign, "movements?size=1").path("totalElements").asInt()).isZero()
        assertThat(request("GET", "/api/v1/warehouse/reports/serial-chain/$asset", foreign).status).isEqualTo(404)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/$viewerId/${setup.inspection}", setup.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(report(viewer, "movements?size=1").path("totalElements").asInt()).isZero()
        assertThat(request("GET", "/api/v1/warehouse/reports/serial-chain/$asset", viewer).status).isEqualTo(404)
        assertThat(request("GET", "/api/v1/warehouse/reports/documents/${receipt.path("id").asString()}/revisions/1/print", viewer).status).isEqualTo(404)
    }

    @Test fun `report filters and export paging fail closed`() {
        val token = tenant()
        for (path in listOf("absent", "serial-chain/${UUID.randomUUID()}")) assertThat(request("GET", "/api/v1/warehouse/reports/$path", token).status).isEqualTo(404)
        for (path in listOf("stock?size=1001", "movements?tenantId=x", "movements?sort=password", "stock/export.csv?size=1",
            "stock/export.csv?page=0", "stock-card?from=2026-01-01T00:00:00Z")) {
            assertThat(request("GET", "/api/v1/warehouse/reports/$path", token).status).describedAs(path).isEqualTo(400)
        }
        assertThat(request("GET", "/api/v1/warehouse/reports/stock", null).status).isEqualTo(401)
    }
}
