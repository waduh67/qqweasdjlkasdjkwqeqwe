package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/** Actual removal, inspected return and explicit same-asset reservation among the ten received ONUs. */
abstract class WarehouseNumericReuseFixture : WarehouseNumericLifecycleFixture() {
    protected fun numericReissue(original: NumericCase, installed: SavedCommand): NumericCase {
        val installation = mapper.readTree(installed.original)
        val asset = installation.path("assetId").asString()
        val serial = original.issue.path("lines").single { it.path("dimension").path("stockIdentityId").asString() == asset }.path("serial").asString()
        val removalOrder = workOrder(original.stock.token, "DISMANTLE", original.customer)
        assign(original.stock.token, removalOrder, original.technician.second)
        assertThat(request("POST", "/api/work-orders/$removalOrder/start", original.technician.first).status).isEqualTo(200)
        val evidence = numericSignature(original.copy(workOrder = removalOrder))
        val removed = saveCommand("/api/customers/${original.customer}/assets/remove", original.technician.first,
            mapper.writeValueAsString(mapOf("assignmentId" to installation.path("assignmentId").asString(),
                "expectedRevision" to 1, "expectedTitleRevision" to 0, "workOrderId" to removalOrder, "evidenceId" to evidence)), "numeric-reuse-remove")
        val intake = saveCommand("/api/v1/warehouse/returns", original.stock.token,
            mapper.writeValueAsString(mapOf("origin" to "ASSET_REMOVAL", "sourceDocumentId" to mapper.readTree(removed.original).path("operationId").asString(),
                "quarantineLocationId" to original.stock.inspection, "evidenceReference" to "Witnessed original ONU recovery")), "numeric-reuse-intake", 201)
        val returned = mapper.readTree(intake.original)
        saveCommand("/api/v1/warehouse/returns/${returned.path("id").asString()}/inspect", original.stock.token,
            mapper.writeValueAsString(mapOf("expectedRevision" to returned.path("revision").asLong(), "measuredQuantityBase" to "1",
                "condition" to "SERVICEABLE", "destinationLocationId" to original.stock.bin, "observedSerial" to serial,
                "evidenceReference" to "Serial matched and recovered ONU tested", "resetConfirmed" to true,
                "resetEvidenceReference" to "Factory reset and previous customer data erased")), "numeric-reuse-inspect")
        assertThat(numericTotals(original)).isEqualTo("917500|82500|0|10|0|0|10|1")

        val created = request("POST", "/api/customers", original.stock.token, mapper.writeValueAsString(mapOf(
            "name" to "Second numeric customer", "address" to "Verified reuse fixture", "areaId" to area(original.stock.token),
            "location" to mapOf("longitude" to 106.84, "latitude" to -6.20))))
        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(created.contentAsString).path("id").asString()
        val work = workOrder(original.stock.token, "PSB", customer)
        assign(original.stock.token, work, original.technician.second)
        putPlan(original.stock.token, work, plan(original.stock.token, work, "[${line(original.stock.onu, "1", "EA")}]"))
        val demand = action(original.stock.token, work, "submit-request", command(original.stock.token, work, 1))
        saveCommand("/api/v1/warehouse/material-requests/${demand.path("demandDocumentId").asString()}/reserve", original.stock.token,
            mapper.writeValueAsString(ReservationRequest(demand.path("demandRevision").asLong(),
                demand.path("revisions").path("workOrderRevision").asLong(), 1,
                lines = listOf(ReservationSelection(UUID.fromString(demand.path("lines").single().path("demandLineId").asString()),
                    stockIdentityId = UUID.fromString(asset))), reason = "Reuse the same inspected and reset device")), "numeric-reuse-reserve")
        val setup = IssueSetup(original.stock, work, original.technician.second)
        val picked = action(original.stock.token, work, "pick", pickBody(setup))
        val issue = action(original.stock.token, work, "dispatch", transitionBody(setup, picked))
        val issued = issue.path("lines").single()
        assertThat(issued.path("dimension").path("stockIdentityId").asString()).isEqualTo(asset)
        val acknowledged = saveCommand("/api/work-orders/$work/materials/acknowledge", original.technician.first,
            mapper.writeValueAsString(MaterialReceiptRequest(UUID.fromString(issue.path("issueId").asString()), issue.path("revision").asLong(),
                issue.path("workOrderRevision").asLong(), "Same physical ONU counted for the second customer",
                listOf(MaterialReceiptSelection(UUID.fromString(issued.path("id").asString()), UUID.fromString(asset), WarehouseBaseUnit.EA,
                    "1", serial = serial)))), "numeric-reuse-ack")
        assertThat(request("POST", "/api/work-orders/$work/start", original.technician.first).status).isEqualTo(200)
        return original.copy(customer = customer, workOrder = work, issue = issue, receipt = mapper.readTree(acknowledged.original),
            usageInput = original.usageInput.copy(expectedRevision = 0, planRevision = 1,
                workOrderRevision = summary(original.stock.token, work).path("revisions").path("workOrderRevision").asLong(), lines = emptyList()))
    }
}
