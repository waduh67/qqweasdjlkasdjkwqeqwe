package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseCustomerRmaAcceptanceIT : WarehouseCustomerRmaFixture() {
    @Test fun `signed original-customer RMA handover accepts custody without another sale or physical posting`() {
        val installed = installRma()
        val case = installed.source
        val signature = removalEvidence(case.receipt.receiver.first, case.work)
        fun physical() = fixture(case.repair.token).transaction {
            scalar("""SELECT concat_ws('|',asset.revision,asset.legal_owner,asset.status,
                (SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg))
                FROM inventory_serialized_asset asset WHERE asset.id='${case.repair.asset}'""")
        }
        val before = physical()
        val body = """{"assignmentId":"${installed.operation}","expectedRevision":0,"expectedTitleRevision":0,"evidenceId":"$signature"}"""
        val accepted = request("POST", "/api/customers/${case.customer}/assets/handover", case.receipt.receiver.first, body, "rma-customer-handover")
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val view = mapper.readTree(accepted.contentAsString)
        assertThat(view.path("handoverState").asString()).isEqualTo("ACCEPTED")
        assertThat(view.path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(view.path("titleRevision").asLong()).isEqualTo(0)
        assertThat(view.path("revision").asLong()).isEqualTo(1)
        assertThat(physical()).isEqualTo(before)
        assertThat(request("POST", "/api/customers/${case.customer}/assets/handover", case.receipt.receiver.first, body, "rma-customer-handover").contentAsString)
            .isEqualTo(accepted.contentAsString)
        assertThat(physical()).isEqualTo(before)
    }

    @Test fun `normal install intent cannot parse or reuse an existing RMA authorization key`() {
        val installed = installRma()
        val case = installed.source
        val source = case.receipt.input.lines.single()
        val body = """{"expectedRevision":${case.receipt.input.workOrderRevision},"assetId":"${source.stockIdentityId}","issueLineId":"${source.issueLineId}","purpose":"INSTALL"}"""
        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/assets/authorize", case.receipt.receiver.first, body, "rma-authorization")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
    }

    @Test fun `assignment history reads both original issue and RMA execution sources`() {
        val installed = installRma()
        val case = installed.source
        val catalog = mapper.readTree(request("GET", "/api/permissions", case.repair.token).contentAsString)
        val permission = catalog.single { it.path("code").asString() == "customer.onu.view" }.path("id").asString()
        val role = request("POST", "/api/roles", case.repair.token,
            mapper.writeValueAsString(mapOf("name" to "RMA history viewer", "permissionIds" to listOf(permission))))
        assertThat(role.status).isEqualTo(201)
        val principal = mapper.readTree(request("GET", "/api/users/${case.receipt.receiver.second}", case.repair.token).contentAsString)
        val roles = principal.path("roleIds").asSequence().map { it.asString() }.toList() + mapper.readTree(role.contentAsString).path("id").asString()
        assertThat(request("PUT", "/api/users/${case.receipt.receiver.second}/access", case.repair.token,
            mapper.writeValueAsString(mapOf("roleIds" to roles, "areaIds" to listOf(area(case.repair.token))))).status).isEqualTo(200)
        val history = authenticated(case.repair.returned.old.installation) {
            context.getBean(InventoryDeploymentApi::class.java).assignmentHistory(case.repair.asset, WarehousePageRequest(0, 25))
        }
        assertThat(history.totalElements).isEqualTo(2)
        assertThat(history.items.map { it.assignmentId }).containsExactlyInAnyOrder(case.repair.returned.old.installation.operation, UUID.fromString(installed.operation))
    }
}
