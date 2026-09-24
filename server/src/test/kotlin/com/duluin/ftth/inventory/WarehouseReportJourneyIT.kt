package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialLifecycleFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode
import java.util.Base64
import java.util.UUID

class WarehouseReportJourneyIT : MaterialLifecycleFixture() {
    override fun stockReceiptCost() = mapOf("totalMinor" to "1000006", "currency" to "IDR")

    private fun result(status: Int, response: org.springframework.mock.web.MockHttpServletResponse): JsonNode {
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(status)
        return mapper.readTree(response.contentAsString)
    }
    private fun report(token: String, path: String) = result(200, request("GET", "/api/v1/warehouse/reports/$path", token))

    @ParameterizedTest @ValueSource(strings = ["LOAN", "SALE"])
    fun `full kilometre and ten ONU journey reports exact physical totals separate currencies and stable replay`(mode: String) {
        val stock = setupReceipt()
        val token = stock.token
        receiveStock(stock, "1000000")
        result(200, request("PUT", "/api/v1/warehouse/skus/${stock.onu}", token,
            """{"expectedRevision":0,"code":"ONU","name":"Optical terminal","category":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false,"allowedOwnershipModes":["LOAN","SALE"]}"""))
        val receiptId = draft(stock, mapper.writeValueAsString(mapOf("skuId" to stock.onu, "quantityBase" to "10",
            "serials" to (1..10).map { mapOf("serial" to "REPORT-ONU-$it") },
            "cost" to mapOf("totalMinor" to "1999995", "currency" to "USD")))).path("id").asString()
        transition(stock, receiptId, "receive", """{"expectedRevision":0}""")
        val receipt = result(200, request("GET", "/api/v1/warehouse/receipts/$receiptId", token))
        val pieces = receipt.path("lines").asSequence().flatMap { line -> line.path("pieces").asSequence().map { piece ->
            mapOf("lineId" to line.path("id").asString(), "stockIdentityId" to piece.path("stockIdentityId").asString(), "quantityBase" to "1", "baseUnit" to "EA")
        } }.toList()
        transition(stock, receiptId, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1,
            "destinationLocationId" to stock.bin, "lines" to pieces)))
        val customer = result(201, request("POST", "/api/customers", token,
            """{"name":"Report customer","address":"Private address must stay hidden","areaId":"${area(token)}","location":{"longitude":106.8,"latitude":-6.2}}""")).path("id").asString()
        val technician = technician(token, setOf("customer.onu.assign"))
        val workOrder = workOrder(token, "PSB", customer)
        assign(token, workOrder, technician.second)
        putPlan(token, workOrder, plan(token, workOrder, "[${line(stock.cable, "100000")},${line(stock.onu, "1", "EA")}]"))
        action(token, workOrder, "submit-request", command(token, workOrder, 1))
        action(token, workOrder, "reserve", command(token, workOrder, 1))
        val transit = create("locations", token, """{"code":"WO_TRANSIT","name":"Transit","kind":"TRANSIT"}""").path("id").asString()
        val field = create("locations", token, """{"code":"FIELD_STOCK","name":"Field custody","kind":"TECHNICIAN","custodianId":"${technician.second}"}""").path("id").asString()
        create("locations", token, """{"code":"CUSTOMER_INSTALLED","name":"Customer equipment","kind":"CUSTOMER_SITE"}""")
        create("locations", token, """{"code":"CONSUMED","name":"Consumption sink","kind":"TRANSIT"}""")
        for (location in listOf(stock.bin, transit, field)) result(200, request("PUT",
            "/api/v1/warehouse/settings/scopes/${technician.second}/$location", token, """{"expectedRevision":0,"active":true}"""))
        val setup = IssueSetup(stock, workOrder, technician.second)
        val picked = action(token, workOrder, "pick", pickBody(setup))
        val dispatched = action(token, workOrder, "dispatch", transitionBody(setup, picked))
        val inTransit = report(token, "transit-backlog").path("items")
        assertThat(inTransit.size()).isEqualTo(2)
        assertThat(inTransit.single { it.path("skuId").asString() == stock.cable }.path("quantity").path("quantityBase").asString()).isEqualTo("100000")
        val selections = dispatched.path("lines").asSequence().map { line -> MaterialReceiptSelection(
            UUID.fromString(line.path("id").asString()), UUID.fromString(line.path("dimension").path("stockIdentityId").asString()),
            WarehouseBaseUnit.valueOf(line.path("baseUnit").asString()), line.path("quantityBase").asString(), serial = line.path("serial").takeUnless { it.isNull }?.asString()) }.toList()
        val ackInput = MaterialReceiptRequest(UUID.fromString(dispatched.path("issueId").asString()), dispatched.path("revision").asLong(),
            dispatched.path("workOrderRevision").asLong(), "signed-100m-one-onu", selections)
        val ackBody = mapper.writeValueAsString(ackInput)
        val ack = result(200, request("POST", "/api/work-orders/$workOrder/materials/acknowledge", technician.first, ackBody, "report-ack"))
        assertThat(report(token, "transit-backlog").path("totalElements").asInt()).isZero()
        val custody = report(token, "custody-aging").path("items")
        assertThat(custody.size()).isEqualTo(2)
        custody.forEach { assertThat(it.path("enteredAt").isNull).isFalse(); assertThat(it.path("ageSeconds").asString().toLong()).isGreaterThanOrEqualTo(0) }
        val cable = selections.single { it.baseUnit == WarehouseBaseUnit.MM }
        val onu = selections.single { it.baseUnit == WarehouseBaseUnit.EA }
        val cableAccepted = ack.path("lines").single { it.path("selection").path("issueLineId").asString() == cable.issueLineId.toString() }
        val usedInput = MaterialUsageRequest(0, 1, summary(token, workOrder).path("revisions").path("workOrderRevision").asLong(),
            MaterialMode.MATERIAL_REQUIRED, "measured-82.500m", listOf(MaterialUsageSelection(UUID.fromString(ack.path("receiptId").asString()),
                cable.issueLineId, UUID.fromString(cableAccepted.path("accepted").path("stockIdentityId").asString()), "82500", WarehouseBaseUnit.MM)))
        val useBody = mapper.writeValueAsString(usedInput)
        val used = result(200, request("POST", "/api/work-orders/$workOrder/materials/report-use", technician.first, useBody, "report-use"))
        val quarantine = create("locations", token, """{"code":"REPORT_RETURN","name":"Return quarantine","kind":"QUARANTINE"}""").path("id").asString()
        val residualInput = MaterialResidualRequest(summary(token, workOrder).path("revisions").path("workOrderRevision").asLong(),
            UUID.fromString(ack.path("receiptId").asString()), cable.issueLineId,
            UUID.fromString(used.path("lines").single().path("remainder").path("stockIdentityId").asString()),
            "17500", WarehouseBaseUnit.MM, UUID.fromString(quarantine), "Return measured unused cable", "signed-sender", UUID.fromString(used.path("usageId").asString()))
        val residual = result(200, request("POST", "/api/work-orders/$workOrder/materials/return", technician.first,
            mapper.writeValueAsString(residualInput), "report-residual"))
        result(200, request("POST", "/api/work-orders/$workOrder/materials/residuals/acknowledge", token,
            """{"documentId":"${residual.path("id").asString()}","expectedRevision":1,"evidenceReference":"signed-receiver"}""", "report-return-ack"))
        val returned = result(201, request("POST", "/api/v1/warehouse/returns", token,
            """{"origin":"MATERIAL_RESIDUAL","sourceDocumentId":"${residual.path("id").asString()}","quarantineLocationId":"$quarantine","evidenceReference":"warehouse-receipt"}""", "report-return"))
        val inspectBody = """{"expectedRevision":0,"measuredQuantityBase":"17500","condition":"SERVICEABLE","destinationLocationId":"${stock.bin}","evidenceReference":"measured-17.500m","resetConfirmed":false}"""
        val inspectPath = "/api/v1/warehouse/returns/${returned.path("id").asString()}/inspect"
        val inspected = result(200, request("POST", inspectPath, token, inspectBody, "report-inspect"))
        result(200, request("POST", "/api/work-orders/$workOrder/start", technician.first))
        val authorized = result(200, request("POST", "/api/work-orders/$workOrder/assets/authorize", technician.first,
            """{"expectedRevision":${summary(token, workOrder).path("revisions").path("workOrderRevision").asLong()},"assetId":"${onu.stockIdentityId}","issueLineId":"${onu.issueLineId}","purpose":"INSTALL","ownershipMode":"$mode"}""", "report-authorize"))
        val installBody = """{"authorizationId":"${authorized.path("authorizationId").asString()}","expectedRevision":0,"topology":null}"""
        val installPath = "/api/customers/$customer/assets/install"
        val installed = result(201, request("POST", installPath, technician.first, installBody, "report-install"))
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=")
        val signature = result(200, mvc.perform(multipart(HttpMethod.PUT, "/api/work-orders/$workOrder/signature")
            .file(MockMultipartFile("file", "report.png", "image/png", png)).param("signerName", "Customer acceptance")
            .header("Authorization", "Bearer ${technician.first}")).andReturn().response)
        result(200, request("POST", "/api/customers/$customer/assets/handover", technician.first,
            """{"assignmentId":"${authorized.path("operationId").asString()}","expectedRevision":0,"expectedTitleRevision":0,"evidenceId":"${signature.path("revisionId").asString()}"}""", "report-handover"))
        val before = fixture(token).transaction { counts() }
        val balances = report(token, "stock").path("items")
        val cableStock = balances.single { it.path("skuId").asString() == stock.cable }
        val onuStock = balances.single { it.path("skuId").asString() == stock.onu }
        assertThat(cableStock.path("available").path("displayQuantity").asString()).isEqualTo("917.500")
        assertThat(cableStock.path("statusBuckets").path("CONSUMED").asString()).isEqualTo("82500")
        assertThat(onuStock.path("available").path("quantityBase").asString()).isEqualTo("9")
        assertThat(onuStock.path("statusBuckets").path("CUSTOMER_INSTALLED").asString()).isEqualTo("1")
        assertThat(report(token, "custody-aging?skuId=${stock.cable}").path("totalElements").asInt()).isZero()
        assertThat(report(token, "transit-backlog").path("totalElements").asInt()).isZero()
        val assignments = report(token, if (mode == "LOAN") "loan-assets" else "sold-assets").path("items")
        assertThat(assignments.size()).isEqualTo(1)
        assertThat(assignments.single().path("assignmentState").asString()).isEqualTo("ACTIVE")
        assertThat(assignments.single().path("legalOwner").asString()).isEqualTo(if (mode == "LOAN") "ISP" else "CUSTOMER")
        val costs = report(token, "work-order-costs")
        assertThat(costs.path("totalElements").asInt()).isEqualTo(2)
        val totals = costs.path("currencyTotals").asSequence().associate { it.path("currency").asString() to it.path("totalMinor").asString() }
        assertThat(totals).containsExactlyInAnyOrderEntriesOf(mapOf("IDR" to "82500", "USD" to "200000"))
        val onuCost = costs.path("items").single { it.path("skuId").asString() == stock.onu }
        assertThat(onuCost.path("sourceTotalMinor").asString()).isEqualTo("1999995")
        assertThat(onuCost.path("sourceBasisQuantityBase").asString()).isEqualTo("10")
        val chain = report(token, "serial-chain/${onu.stockIdentityId}")
        assertThat(chain.path("items").any { it.path("movementKind").asString() == "DEPLOY" }).isTrue()
        assertThat(chain.toString()).doesNotContain("Private address", "signerName", "evidence", "objectKey")
        assertThat(result(200, request("POST", "/api/work-orders/$workOrder/materials/acknowledge", technician.first, ackBody, "report-ack"))).isEqualTo(ack)
        assertThat(result(200, request("POST", inspectPath, token, inspectBody, "report-inspect"))).isEqualTo(inspected)
        assertThat(result(201, request("POST", installPath, technician.first, installBody, "report-install"))).isEqualTo(installed)
        assertThat(report(token, "work-order-costs")).isEqualTo(costs)
        assertThat(fixture(token).transaction { counts() }).isEqualTo(before)
    }
}
