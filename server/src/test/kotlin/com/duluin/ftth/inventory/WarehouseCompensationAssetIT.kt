package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class WarehouseCompensationAssetIT : WarehouseReturnAssetFixture() {
    override fun stockReceiptCost() = mapOf("totalMinor" to "200000", "currency" to "IDR")

    @ParameterizedTest
    @ValueSource(strings = ["LOSS", "SCRAP"])
    fun `a restored loan may be inspected and reused but its old disposition cannot undo the new installation`(dispositionAction: String) {
        val returned = recoveredReturn()
        val old = returned.old
        val receipt = old.installation.receipt
        val stock = receipt.stock
        val admin = stock.token
        val asset = receipt.input.lines.single().stockIdentityId
        val sink = create("locations", admin,
            """{"code":"ASSET_EXCEPTION","name":"Recorded device disposition","kind":"${if (dispositionAction == "LOSS") "LOST" else "DISPOSED"}"}""")
            .path("id").asString()
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        for (location in listOf(returned.quarantine, sink)) {
            assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/$location", admin,
                """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        }
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["${returned.quarantine}","$sink"],"rules":[{"operation":"$dispositionAction","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]},{"operation":"ADJUSTMENT","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val original = request("POST", "/api/v1/warehouse/dispositions", admin,
            """{"sourceDocumentId":"${returned.id}","expectedRevision":${returned.revision},"stockIdentityId":"$asset","quantityBase":"1","baseUnit":"EA","destinationLocationId":"$sink","action":"$dispositionAction","reason":"Independently recorded physical exception","evidenceReference":"device-disposition-assessment"}""", "device-disposition")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val originalId = mapper.readTree(original.contentAsString).path("id").asString()
        approve(admin, checker.first, originalId, "device-disposition")
        val correctionPath = "/api/v1/warehouse/dispositions/$originalId/compensations"
        val correctionBody = """{"expectedRevision":1,"expectedReturnRevision":${returned.revision + 1},"destinationLocationId":"${returned.quarantine}","reason":"Witnessed recovery of the original physical device","evidenceReference":"same-serial-recovery-assessment"}"""
        val correction = request("POST", correctionPath, admin, correctionBody, "device-correction")
        assertThat(correction.status).withFailMessage(correction.contentAsString).isEqualTo(201)
        approve(admin, checker.first, mapper.readTree(correction.contentAsString).path("id").asString(), "device-correction")
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',status,condition,legal_owner) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("QUARANTINE|QUARANTINE|ISP")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset' AND ended_at IS NOT NULL")).isEqualTo("1")
        }
        val inspected = request("POST", "/api/v1/warehouse/returns/${returned.id}/inspect", admin,
            """{"expectedRevision":${returned.revision + 2},"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"${stock.bin}","evidenceReference":"recovered-device-tested","observedSerial":"${receipt.input.lines.single().serial}","resetConfirmed":true,"resetEvidenceReference":"factory-reset-and-customer-data-erasure"}""", "corrected-device-inspection")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)

        val customerResponse = request("POST", "/api/customers", admin,
            """{"code":"CORRECTED-REUSE","name":"New loan customer","address":"Test address","location":{"longitude":106.9,"latitude":-6.2},"areaId":"${area(admin)}"}""")
        assertThat(customerResponse.status).withFailMessage(customerResponse.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(customerResponse.contentAsString).path("id").asString()
        val work = workOrder(admin, "PSB", customer)
        assign(admin, work, receipt.receiver.second)
        putPlan(admin, work, plan(admin, work, "[${line(stock.onu, "1", "EA")}]"))
        action(admin, work, "submit-request", command(admin, work, 1))
        action(admin, work, "reserve", command(admin, work, 1))
        val setup = IssueSetup(stock, work, receipt.receiver.second)
        val picked = action(admin, work, "pick", pickBody(setup))
        val issued = action(admin, work, "dispatch", transitionBody(setup, picked))
        val selected = issued.path("lines").single()
        assertThat(selected.path("dimension").path("stockIdentityId").asString()).isEqualTo(asset.toString())
        val input = MaterialReceiptRequest(UUID.fromString(issued.path("issueId").asString()), issued.path("revision").asLong(),
            issued.path("workOrderRevision").asLong(), "recovered-device-delivery", listOf(MaterialReceiptSelection(
                UUID.fromString(selected.path("id").asString()), asset, WarehouseBaseUnit.EA, "1", serial = selected.path("serial").asString())))
        val replacement = ReceiptCase(stock, work, receipt.receiver, receipt.transit, receipt.field, input)
        assertThat(acknowledge(replacement, key = "corrected-device-ack").status).isEqualTo(200)
        val authorization = request("POST", "/api/work-orders/$work/assets/authorize", receipt.receiver.first,
            """{"expectedRevision":${summary(admin, work).path("revisions").path("workOrderRevision").asLong()},"assetId":"$asset","issueLineId":"${selected.path("id").asString()}","purpose":"INSTALL"}""", "corrected-reuse-authorize")
        assertThat(authorization.status).withFailMessage(authorization.contentAsString).isEqualTo(200)
        val grant = mapper.readTree(authorization.contentAsString)
        val installation = Installation(replacement, UUID.fromString(customer), UUID.fromString(grant.path("authorizationId").asString()),
            UUID.fromString(grant.path("operationId").asString()))
        val installed = consume(installation, "corrected-reuse-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        val current = mapper.readTree(request("GET", "/api/v1/warehouse/returns/${returned.id}", admin).contentAsString)
        val repeated = request("POST", correctionPath, admin,
            mapper.writeValueAsString(WarehouseCompensationInput(1, current.path("revision").asLong(), UUID.fromString(returned.quarantine),
                "Attempt to undo a device that was already reused", "same-historical-disposition")), "undo-reused-device")
        assertThat(repeated.status).withFailMessage(repeated.contentAsString).isEqualTo(409)
        assertThat(mapper.readTree(repeated.contentAsString).path("code").asString()).isEqualTo("SOURCE_NOT_VERIFIED")
        assertThat(request("POST", correctionPath, admin, correctionBody, "device-correction").contentAsString).isEqualTo(correction.contentAsString)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='REVERSAL'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset'")).isEqualTo("2")
            assertThat(scalar("SELECT customer_id FROM inventory_asset_assignment WHERE asset_id='$asset' AND ended_at IS NULL")).isEqualTo(customer)
            assertThat(scalar("SELECT customer_id FROM inventory_asset_assignment WHERE id='${old.installation.operation}' AND ended_at IS NOT NULL"))
                .isEqualTo(old.installation.customer.toString())
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='$asset'")).isEqualTo("2")
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner,custody_owner_id) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("CUSTOMER_INSTALLED|ISP|$customer")
        }
    }

    private fun approve(admin: String, checker: String, document: String, key: String) {
        val approval = request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "$key-approval")
        assertThat(approval.status).withFailMessage(approval.contentAsString).isEqualTo(201)
        val decision = request("POST", "/api/v1/warehouse/approvals/decide", checker,
            """{"requestId":"${mapper.readTree(approval.contentAsString).path("requestId").asString()}","expectedRevision":0,"decision":"APPROVE","reason":"Independent serial and source evidence reviewed"}""", "$key-decision")
        assertThat(decision.status).withFailMessage(decision.contentAsString).isEqualTo(200)
    }
}
