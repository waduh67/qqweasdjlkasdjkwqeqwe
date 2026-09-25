package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode
import java.util.Base64
import java.util.UUID

/** Full physical reference fixture. Every stock/asset mutation uses its actual HTTP controller. */
abstract class WarehouseNumericLifecycleFixture : WarehouseFulfillmentFixture() {
    protected data class NumericCase(val stock: Setup, val technician: Pair<String, String>, val customer: String,
        val workOrder: String, val issue: JsonNode, val receipt: JsonNode, val usageInput: MaterialUsageRequest)
    protected data class SavedCommand(val path: String, val token: String, val body: String, val key: String,
        val status: Int, val original: String)
    protected data class NumericReturn(val caseId: String, val residualId: String, val remnant: String,
        val inspection: SavedCommand)

    protected fun numericCase(): NumericCase {
        val stock = setupReceipt()
        receiveStock(stock, "1000000")
        assertThat(request("PUT", "/api/v1/warehouse/skus/${stock.onu}", stock.token,
            """{"expectedRevision":0,"code":"ONU","name":"ONU","category":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false,"allowedOwnershipModes":["LOAN","SALE"]}""").status).isEqualTo(200)
        val serials = (1..10).map { mapOf("serial" to "NUMERIC-ONU-$it") }
        val document = draft(stock, mapper.writeValueAsString(mapOf("skuId" to stock.onu,
            "quantityBase" to "10", "serials" to serials))).path("id").asString()
        transition(stock, document, "receive", """{"expectedRevision":0}""")
        val received = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$document", stock.token).contentAsString)
        val pieces = received.path("lines").asSequence().flatMap { line -> line.path("pieces").asSequence().map { piece ->
            mapOf("lineId" to line.path("id").asString(), "stockIdentityId" to piece.path("stockIdentityId").asString(),
                "quantityBase" to "1", "baseUnit" to "EA")
        } }.toList()
        transition(stock, document, "putaway", mapper.writeValueAsString(mapOf("expectedRevision" to 1,
            "destinationLocationId" to stock.bin, "lines" to pieces)))
        val technician = technician(stock.token, setOf("customer.onu.assign"))
        val customerResponse = request("POST", "/api/customers", stock.token,
            mapper.writeValueAsString(mapOf("name" to "Numeric customer", "address" to "Reference fixture",
                "location" to mapOf("longitude" to 106.82, "latitude" to -6.18), "areaId" to area(stock.token))))
        assertThat(customerResponse.status).withFailMessage(customerResponse.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(customerResponse.contentAsString).path("id").asString()
        val workOrder = workOrder(stock.token, "PSB", customer)
        assign(stock.token, workOrder, technician.second)
        putPlan(stock.token, workOrder, plan(stock.token, workOrder, "[${line(stock.cable)},${line(stock.onu, "1", "EA")}]"))
        action(stock.token, workOrder, "submit-request", command(stock.token, workOrder, 1))
        action(stock.token, workOrder, "reserve", command(stock.token, workOrder, 1))
        val transit = create("locations", stock.token, """{"code":"WO_TRANSIT","name":"Delivery","kind":"TRANSIT"}""").path("id").asString()
        val field = create("locations", stock.token,
            """{"code":"FIELD_STOCK","name":"Technician custody","kind":"TECHNICIAN","custodianId":"${technician.second}"}""").path("id").asString()
        create("locations", stock.token, """{"code":"CONSUMED","name":"Installed cable","kind":"TRANSIT"}""")
        create("locations", stock.token, """{"code":"CUSTOMER_INSTALLED","name":"Installed devices","kind":"CUSTOMER_SITE"}""")
        for (location in listOf(stock.bin, transit, field)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${technician.second}/$location", stock.token,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val setup = IssueSetup(stock, workOrder, technician.second)
        val picked = action(stock.token, workOrder, "pick", pickBody(setup))
        val issue = action(stock.token, workOrder, "dispatch", transitionBody(setup, picked))
        assertThat(issue.path("lines").size()).isEqualTo(2)
        val input = MaterialReceiptRequest(UUID.fromString(issue.path("issueId").asString()), issue.path("revision").asLong(),
            issue.path("workOrderRevision").asLong(), "Both parties counted one ONU and measured100m", issue.path("lines").asSequence().map { row ->
                val unit = WarehouseBaseUnit.valueOf(row.path("baseUnit").asString())
                MaterialReceiptSelection(UUID.fromString(row.path("id").asString()),
                    UUID.fromString(row.path("dimension").path("stockIdentityId").asString()), unit,
                    if (unit == WarehouseBaseUnit.MM) "100000" else "1", "0", serial = row.path("serial").takeUnless { it.isNull || it.isMissingNode }?.asString())
            }.toList())
        val ack = request("POST", "/api/work-orders/$workOrder/materials/acknowledge", technician.first,
            mapper.writeValueAsString(input), "numeric-ack")
        assertThat(ack.status).withFailMessage(ack.contentAsString).isEqualTo(200)
        val receipt = mapper.readTree(ack.contentAsString)
        assertThat(request("POST", "/api/work-orders/$workOrder/start", technician.first).status).isEqualTo(200)
        val cable = input.lines.single { it.baseUnit == WarehouseBaseUnit.MM }
        val accepted = receipt.path("lines").single { it.path("selection").path("issueLineId").asString() == cable.issueLineId.toString() }
        val currentRevision = summary(stock.token, workOrder).path("revisions").path("workOrderRevision").asLong()
        return NumericCase(stock, technician, customer, workOrder, issue, receipt,
            MaterialUsageRequest(0, 1, currentRevision, MaterialMode.MATERIAL_REQUIRED, "Measured82.500m",
                listOf(MaterialUsageSelection(UUID.fromString(receipt.path("receiptId").asString()), cable.issueLineId,
                    UUID.fromString(accepted.path("accepted").path("stockIdentityId").asString()), "82500", WarehouseBaseUnit.MM))))
    }

    protected fun numericUse(case: NumericCase, key: String = "numeric-use"): SavedCommand = saveCommand(
        "/api/work-orders/${case.workOrder}/materials/report-use", case.technician.first,
        mapper.writeValueAsString(case.usageInput), key)

    protected fun numericInstall(case: NumericCase): SavedCommand {
        val serial = case.issue.path("lines").single { !it.path("serial").isNull && !it.path("serial").isMissingNode }
        val revision = summary(case.stock.token, case.workOrder).path("revisions").path("workOrderRevision").asLong()
        val authorization = saveCommand("/api/work-orders/${case.workOrder}/assets/authorize", case.technician.first,
            mapper.writeValueAsString(mapOf("expectedRevision" to revision, "assetId" to serial.path("dimension").path("stockIdentityId").asString(),
                "issueLineId" to serial.path("id").asString(), "purpose" to "INSTALL")), "numeric-authorize")
        return saveCommand("/api/customers/${case.customer}/assets/install", case.technician.first,
            mapper.writeValueAsString(mapOf("authorizationId" to mapper.readTree(authorization.original).path("authorizationId").asString(),
                "expectedRevision" to 0, "topology" to null)), "numeric-install", 201)
    }

    protected fun numericHandover(case: NumericCase, installed: SavedCommand): UUID {
        val evidenceId = numericSignature(case)
        val assignment = mapper.readTree(installed.original).path("assignmentId").asString()
        saveCommand("/api/customers/${case.customer}/assets/handover", case.technician.first,
            mapper.writeValueAsString(mapOf("assignmentId" to assignment, "expectedRevision" to 0,
                "expectedTitleRevision" to 0, "evidenceId" to evidenceId)), "numeric-loan-handover")
        return evidenceId
    }

    protected fun numericSignature(case: NumericCase): UUID {
        val signature = mvc.perform(multipart(HttpMethod.PUT, "/api/work-orders/${case.workOrder}/signature")
            .file(MockMultipartFile("file", "signed.png", "image/png", png()))
            .param("signerName", "Numeric customer").header("Authorization", "Bearer ${case.technician.first}")).andReturn().response
        assertThat(signature.status).withFailMessage(signature.contentAsString).isEqualTo(200)
        return UUID.fromString(mapper.readTree(signature.contentAsString).path("revisionId").asString())
    }

    protected fun numericReturn(case: NumericCase, usage: SavedCommand): NumericReturn {
        val use = mapper.readTree(usage.original)
        val source = case.usageInput.lines.single()
        val remnant = use.path("lines").single().path("remainder").path("stockIdentityId").asString()
        val revision = summary(case.stock.token, case.workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = MaterialResidualRequest(revision, source.receiptId, source.issueLineId, UUID.fromString(remnant),
            "17500", WarehouseBaseUnit.MM, UUID.fromString(case.stock.inspection), "Unused17.500m", "Signed remnant return",
            UUID.fromString(use.path("usageId").asString()))
        val dispatched = saveCommand("/api/work-orders/${case.workOrder}/materials/return", case.technician.first,
            mapper.writeValueAsString(input), "numeric-return")
        val residual = mapper.readTree(dispatched.original).path("id").asString()
        saveCommand("/api/work-orders/${case.workOrder}/materials/residuals/acknowledge", case.stock.token,
            mapper.writeValueAsString(mapOf("documentId" to residual, "expectedRevision" to 1,
                "evidenceReference" to "Warehouse received measured remnant")), "numeric-return-ack")
        val intake = saveCommand("/api/v1/warehouse/returns", case.stock.token,
            mapper.writeValueAsString(mapOf("origin" to "MATERIAL_RESIDUAL", "sourceDocumentId" to residual,
                "quarantineLocationId" to case.stock.inspection, "evidenceReference" to "Intact jacket and measured17.500m")), "numeric-return-intake", 201)
        val id = mapper.readTree(intake.original).path("id").asString()
        assertThat(mapper.readTree(intake.original).path("stockIdentityId").asString()).isEqualTo(remnant)
        val inspected = saveCommand("/api/v1/warehouse/returns/$id/inspect", case.stock.token,
            mapper.writeValueAsString(mapOf("expectedRevision" to 0, "measuredQuantityBase" to "17500", "condition" to "SERVICEABLE",
                "destinationLocationId" to case.stock.bin, "evidenceReference" to "Measured remnant released to stock", "resetConfirmed" to false)), "numeric-return-inspect")
        assertThat(mapper.readTree(inspected.original).path("state").asString()).isEqualTo("ACCEPTED")
        return NumericReturn(id, residual, remnant, inspected)
    }

    protected fun numericComplete(case: NumericCase, signature: UUID) {
        val kinds = listOf("FAT", "ODP", "DROPCORE", "ONT", "ONU", "OPTICAL_BEFORE", "OPTICAL_AFTER", "TECHNICIAN_SIGNATURE", "LOCATION")
        val artifacts = kinds.map { kind ->
            val response = mvc.perform(multipart("/api/work-orders/${case.workOrder}/evidence")
                .file(MockMultipartFile("file", "$kind.png", "image/png", png())).param("kind", kind)
                .header("Authorization", "Bearer ${case.technician.first}")).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            mapOf("kind" to kind, "revisionId" to mapper.readTree(response.contentAsString).path("revisionId").asString())
        } + mapOf("kind" to "CUSTOMER_ACKNOWLEDGEMENT", "revisionId" to signature.toString())
        val proof = request("GET", "/api/work-orders/${case.workOrder}/proof-of-work", case.technician.first)
        assertThat(proof.status).isEqualTo(200)
        saveCommand("/api/work-orders/${case.workOrder}/complete", case.technician.first,
            mapper.writeValueAsString(mapOf("proofRevision" to mapper.readTree(proof.contentAsString).path("revision").asString(),
                "artifacts" to artifacts, "resolutionNote" to "Measured cable and installed warehouse ONU")), "numeric-complete")
    }

    protected fun saveCommand(path: String, token: String, body: String, key: String, status: Int = 200): SavedCommand {
        val response = request("POST", path, token, body, key)
        assertThat(response.status).withFailMessage("$path: ${response.contentAsString}").isEqualTo(status)
        return SavedCommand(path, token, body, key, status, response.contentAsString)
    }

    protected fun numericTotals(case: NumericCase): String = fixture(case.stock.token).transaction {
        assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE base_unit='MM'")).isEqualTo("1000000")
        assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE base_unit='EA'")).isEqualTo("10")
        scalar("""SELECT concat_ws('|',
          (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE base_unit='MM' AND status='AVAILABLE'),
          (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE base_unit='MM' AND status='CONSUMED'),
          (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE base_unit='MM' AND custody_owner_kind='TECHNICIAN'),
          (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE base_unit='EA' AND status='AVAILABLE'),
          (SELECT count(*) FROM inventory_asset_assignment WHERE ended_at IS NULL),
          (SELECT count(*) FROM onu WHERE retired_at IS NULL AND warehouse_admission='VERIFIED'),
          (SELECT count(*) FROM inventory_serialized_asset),
          (SELECT count(*) FROM inventory_asset_handover))""")
    }
    protected fun numericAudit(case: NumericCase): String = fixture(case.stock.token).transaction {
        scalar("""SELECT concat_ws('|',(SELECT count(*) FROM inventory_movement),
          (SELECT count(*) FROM inventory_operation),(SELECT count(*) FROM inventory_customer_material_fact),
          (SELECT count(*) FROM inventory_asset_assignment),(SELECT count(*) FROM inventory_segment),
          (SELECT count(*) FROM inventory_return_case))""")
    }
    private fun png() = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jK1cAAAAASUVORK5CYII=")
}
