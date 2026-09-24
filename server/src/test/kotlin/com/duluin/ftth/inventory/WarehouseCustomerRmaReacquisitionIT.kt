package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseCustomerRmaReacquisitionIT : WarehouseCustomerRmaFixture() {
    @Test fun `independently reacquired RMA can return through inspection and normal issue to another customer`() {
        val installed = installRma()
        val case = installed.source
        val receipt = case.receipt
        val admin = case.repair.token
        val asset = case.repair.asset
        val signature = removalEvidence(receipt.receiver.first, case.work)
        val accepted = request("POST", "/api/customers/${case.customer}/assets/handover", receipt.receiver.first,
            """{"assignmentId":"${installed.operation}","expectedRevision":0,"expectedTitleRevision":0,"evidenceId":"$signature"}""", "rma-accept")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val handover = mapper.readTree(accepted.contentAsString).path("acceptedHandover").path("handoverId").asString()
        val checker = user(admin, setOf("inventory.approval.view", "inventory.approval.decide"))
        val principal = mapper.readTree(request("GET", "/api/users/${checker.second}", admin).contentAsString)
        assertThat(request("PUT", "/api/users/${checker.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(admin))))).status).isEqualTo(200)
        val installedLocation = fixture(admin).transaction {
            scalar("SELECT location_id FROM inventory_serialized_asset WHERE id='$asset'")
        }
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${checker.second}/$installedLocation", admin,
            """{"expectedRevision":0,"active":true}""").status).isEqualTo(200)
        val policy = request("PUT", "/api/v1/warehouse/settings/policy", admin,
            """{"expectedRevision":0,"currency":"IDR","expiryHours":24,"warehouseIds":["$installedLocation"],"rules":[{"operation":"TITLE_REACQUISITION","tiers":[{"minimumMinor":"1","userIds":["${checker.second}"],"roleIds":[]}]}]}""")
        assertThat(policy.status).withFailMessage(policy.contentAsString).isEqualTo(200)
        val correction = request("POST", "/api/v1/warehouse/asset-title-corrections", admin,
            """{"assignmentId":"${installed.operation}","sourceHandoverId":"$handover","expectedAssignmentRevision":1,"expectedTitleRevision":0,"targetOwner":"ISP","reason":"Customer approved reacquisition after repair","evidenceId":"$signature"}""", "rma-reacquire")
        assertThat(correction.status).withFailMessage(correction.contentAsString).isEqualTo(201)
        val document = mapper.readTree(correction.contentAsString).path("documentId").asString()
        val submitted = request("POST", "/api/v1/warehouse/approvals/request", admin,
            """{"sourceDocumentId":"$document","sourceRevision":0}""", "rma-title-request")
        assertThat(submitted.status).withFailMessage(submitted.contentAsString).isEqualTo(201)
        val approval = mapper.readTree(submitted.contentAsString).path("requestId").asString()
        fixture(admin).transaction {
            assertThat(scalar("SELECT legal_owner FROM inventory_serialized_asset WHERE id='$asset'")).isEqualTo("CUSTOMER")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer WHERE assignment_id='${installed.operation}'")).isEqualTo("0")
        }
        val decision = """{"requestId":"$approval","expectedRevision":0,"decision":"APPROVE","reason":"Independent title verification"}"""
        val denied = request("POST", "/api/v1/warehouse/approvals/decide", admin, decision, "rma-self-approval")
        assertThat(denied.status).withFailMessage(denied.contentAsString).isIn(403, 409)
        val approved = request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "rma-title-approval")
        assertThat(approved.status).withFailMessage(approved.contentAsString).isEqualTo(200)
        assertThat(request("POST", "/api/v1/warehouse/approvals/decide", checker.first, decision, "rma-title-approval").contentAsString)
            .isEqualTo(approved.contentAsString)
        val report = request("GET", "/api/customers/${case.customer}/assets/ownership", admin)
        assertThat(report.status).withFailMessage(report.contentAsString).isEqualTo(200)
        val title = mapper.readTree(report.contentAsString).single()
        assertThat(title.path("legalOwner").asString()).isEqualTo("ISP")
        assertThat(title.path("titleRevision").asLong()).isEqualTo(1)
        val work = workOrder(admin, "DISMANTLE", case.customer.toString())
        assign(admin, work, receipt.receiver.second)
        val removed = request("POST", "/api/customers/${case.customer}/assets/remove", receipt.receiver.first,
            """{"assignmentId":"${installed.operation}","expectedRevision":2,"expectedTitleRevision":1,"workOrderId":"$work","evidenceId":"${removalEvidence(receipt.receiver.first, work)}"}""", "rma-reacquired-removal")
        assertThat(removed.status).withFailMessage(removed.contentAsString).isEqualTo(200)
        val source = mapper.readTree(removed.contentAsString).path("operationId").asString()
        val returned = request("POST", "/api/v1/warehouse/returns", admin,
            """{"origin":"ASSET_REMOVAL","sourceDocumentId":"$source","quarantineLocationId":"${case.repair.returned.quarantine}","evidenceReference":"reacquired-device-witnessed"}""", "rma-reacquired-return")
        assertThat(returned.status).withFailMessage(returned.contentAsString).isEqualTo(201)
        val intake = mapper.readTree(returned.contentAsString)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("ISP|QUARANTINE")
        }
        val inspected = request("POST", "/api/v1/warehouse/returns/${intake.path("id").asString()}/inspect", admin,
            """{"expectedRevision":${intake.path("revision").asLong()},"measuredQuantityBase":"1","condition":"SERVICEABLE","destinationLocationId":"${receipt.stock.bin}","evidenceReference":"reacquired-condition","observedSerial":"${case.repair.serial}","resetConfirmed":true,"resetEvidenceReference":"reacquired-factory-reset"}""", "rma-reacquired-inspection")
        assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
        issueToNewCustomer(case)
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_asset_title_transfer WHERE assignment_id='${installed.operation}'")).isEqualTo("1")
            assertThat(scalar("SELECT snapshot->>'legal_owner' FROM inventory_asset_assignment_history WHERE assignment_id='${installed.operation}' AND revision=1")).isEqualTo("CUSTOMER")
            assertThat(scalar("SELECT legal_owner FROM inventory_asset_assignment WHERE id='${case.repair.returned.old.installation.operation}'")).isEqualTo("CUSTOMER")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='$asset'")).isEqualTo("3")
            assertThat(scalar("SELECT count(*) FROM onu WHERE asset_id='$asset'")).isEqualTo("3")
        }
    }

    private fun issueToNewCustomer(case: RmaCase) {
        val receipt = case.receipt
        val stock = receipt.stock
        val admin = stock.token
        val asset = case.repair.asset
        val customerResponse = request("POST", "/api/customers", admin,
            """{"code":"REACQUIRED-CUSTOMER","name":"New customer","address":"Test address","location":{"longitude":106.9,"latitude":-6.2},"areaId":"${area(admin)}"}""")
        assertThat(customerResponse.status).withFailMessage(customerResponse.contentAsString).isEqualTo(201)
        val customer = mapper.readTree(customerResponse.contentAsString).path("id").asString()
        val work = workOrder(admin, "PSB", customer)
        assign(admin, work, receipt.receiver.second)
        putPlan(admin, work, plan(admin, work, "[${line(stock.onu, "1", "EA")}]"))
        action(admin, work, "submit-request", command(admin, work, 1))
        action(admin, work, "reserve", command(admin, work, 1))
        val setup = IssueSetup(stock, work, receipt.receiver.second)
        val issued = action(admin, work, "dispatch", transitionBody(setup, action(admin, work, "pick", pickBody(setup))))
        val selected = issued.path("lines").single()
        assertThat(selected.path("dimension").path("stockIdentityId").asString()).isEqualTo(asset.toString())
        val input = MaterialReceiptRequest(UUID.fromString(issued.path("issueId").asString()), issued.path("revision").asLong(),
            issued.path("workOrderRevision").asLong(), "reacquired-device-delivery", listOf(MaterialReceiptSelection(
                UUID.fromString(selected.path("id").asString()), asset, WarehouseBaseUnit.EA, "1", serial = selected.path("serial").asString())))
        val replacement = ReceiptCase(stock, work, receipt.receiver, receipt.transit, receipt.field, input)
        assertThat(acknowledge(replacement, key = "reacquired-ack").status).isEqualTo(200)
        val authorization = request("POST", "/api/work-orders/$work/assets/authorize", receipt.receiver.first,
            """{"expectedRevision":${summary(admin, work).path("revisions").path("workOrderRevision").asLong()},"assetId":"$asset","issueLineId":"${selected.path("id").asString()}","purpose":"INSTALL"}""", "reacquired-authorize")
        assertThat(authorization.status).withFailMessage(authorization.contentAsString).isEqualTo(200)
        val grant = mapper.readTree(authorization.contentAsString)
        val installation = Installation(replacement, UUID.fromString(customer), UUID.fromString(grant.path("authorizationId").asString()),
            UUID.fromString(grant.path("operationId").asString()))
        val installed = consume(installation, "reacquired-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        fixture(admin).transaction {
            assertThat(scalar("SELECT concat_ws('|',status,legal_owner,custody_owner_id) FROM inventory_serialized_asset WHERE id='$asset'"))
                .isEqualTo("CUSTOMER_INSTALLED|ISP|$customer")
        }
    }
}
