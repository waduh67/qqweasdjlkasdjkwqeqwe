package com.duluin.ftth.inventory

import com.duluin.ftth.fulfillment.MaterialWorkflowFixture
import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode

abstract class WarehouseIssueFixture : MaterialWorkflowFixture() {
    protected data class IssueSetup(val stock: Setup, val workOrder: String, val technicianId: String)
    protected fun issuedSetup(serials: Int = 0, requested: String = "100000"): IssueSetup {
        val stock = setupReceipt()
        if (serials == 0) receiveStock(stock, "1000000") else {
            assertThat(request("PUT", "/api/v1/warehouse/skus/${stock.onu}", stock.token,
                """{"expectedRevision":0,"code":"ONU","name":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false}""").status).isEqualTo(200)
            val serialInput = (1..serials).joinToString(",") { """{"serial":"SERIAL-$it"}""" }
            val receipt = draft(stock, """{"skuId":"${stock.onu}","quantityBase":"$serials","serials":[$serialInput]}""").path("id").asString()
            transition(stock, receipt, "receive", """{"expectedRevision":0}""")
            val document = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receipt", stock.token).contentAsString)
            val pieces = document.path("lines").asSequence().flatMap { source -> source.path("pieces").asSequence().map { piece ->
                mapOf("lineId" to source.path("id").asString(), "stockIdentityId" to piece.path("stockIdentityId").asString(),
                    "baseUnit" to "EA", "quantityBase" to "1") } }.toList()
            transition(stock, receipt, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1,
                "destinationLocationId" to stock.bin, "lines" to pieces)))
        }
        val technician = technician(stock.token)
        val workOrder = workOrder(stock.token)
        assign(stock.token, workOrder, technician.second)
        putPlan(stock.token, workOrder, plan(stock.token, workOrder, "[${line(if (serials == 0) stock.cable else stock.onu,
            if (serials == 0) requested else serials.toString(), if (serials == 0) "MM" else "EA")}]"))
        action(stock.token, workOrder, "submit-request", command(stock.token, workOrder, 1))
        action(stock.token, workOrder, "reserve", command(stock.token, workOrder, 1))
        create("locations", stock.token, """{"code":"WO_TRANSIT","name":"Transit","kind":"TRANSIT"}""")
        return IssueSetup(stock, workOrder, technician.second)
    }
    protected fun pickBody(setup: IssueSetup, quantity: String? = null): String {
        val summary = summary(setup.stock.token, setup.workOrder)
        val response = request("GET", "/api/v1/warehouse/material-requests/allocations/${setup.workOrder}", setup.stock.token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        val allocations = mapper.readTree(response.contentAsString).filter { it.path("state").asString() == "OPEN" && it.path("reservedUnpickedBase").asString() != "0" }
        return mapper.writeValueAsString(mapOf("expectedRevision" to 1,
            "workOrderRevision" to summary.path("revisions").path("workOrderRevision").asLong(), "demandRevision" to summary.path("demandRevision").asLong(),
            "lines" to allocations.map { line -> mapOf("reservationId" to line.path("reservationId").asString(),
                "expectedRevision" to line.path("reservationRevision").asLong(), "stockIdentityId" to line.path("stockIdentityId").asString(),
                "stockRevision" to 0, "quantityBase" to (quantity ?: line.path("reservedUnpickedBase").asString()), "baseUnit" to line.path("baseUnit").asString()) }))
    }
    protected fun transitionBody(setup: IssueSetup, issue: JsonNode, partial: Boolean = false): String {
        val state = summary(setup.stock.token, setup.workOrder)
        return mapper.writeValueAsString(mapOf("issueId" to issue.path("issueId").asString(), "expectedRevision" to issue.path("revision").asLong(),
            "workOrderRevision" to state.path("revisions").path("workOrderRevision").asLong(), "planRevision" to 1,
            "demandRevision" to state.path("demandRevision").asLong(), "partial" to partial, "reason" to "Verified handover"))
    }
    protected fun issueRequest(setup: IssueSetup, action: String, body: String, key: String = java.util.UUID.randomUUID().toString()) =
        request("POST", "/api/work-orders/${setup.workOrder}/materials/$action", setup.stock.token, body, key)
}
