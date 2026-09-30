package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseCustomerRmaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MyMaterialsRmaIT : WarehouseCustomerRmaFixture() {
    private fun get(path: String, token: String): tools.jackson.databind.JsonNode {
        val response = request("GET", path, token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store")
        assertThat(response.contentAsString).doesNotContain("payloadHash", "sessionId", "costTotal")
        return mapper.readTree(response.contentAsString)
    }
    @Test fun `own RMA inbox supplies actual acknowledgement and original customer reinstall references without ordinary ISP custody`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        val actor = case.receipt.receiver.first
        val root = "/api/v1/warehouse/my-materials"
        val path = "$root/${case.work}/rmas"
        val jobs = get(root, actor).path("items")
        val job = jobs.single { it.path("id").asString() == case.work }
        val context = get("$root/${case.work}", actor)
        assertThat(job.path("code").asString()).isEqualTo(context.path("code").asString())
        val before = fixture(case.repair.token).transaction { scalar("SELECT count(*) FROM inventory_movement") }
        val selected = get(path, actor).path("items").single()
        val view = selected.path("handover")
        assertThat(view.path("id").asString()).isEqualTo(outbound.path("id").asString())
        assertThat(view.path("state").asString()).isEqualTo("DISPATCHED")
        assertThat(view.path("customerId").asString()).isEqualTo(case.customer.toString())
        assertThat(view.path("legalOwner").asString()).isEqualTo("CUSTOMER")
        assertThat(selected.path("senderName").asString()).isNotBlank()
        assertThat(selected.path("technicianName").asString()).isNotBlank()
        assertThat(get("$path/${view.path("id").asString()}", actor)).isEqualTo(selected)
        assertThat(fixture(case.repair.token).transaction { scalar("SELECT count(*) FROM inventory_movement") }).isEqualTo(before)
        assertThat(get("$root/${case.work}/custody", actor).path("totalElements").asLong()).isZero()
        val ack = """{"expectedRevision":${view.path("revision").asLong()},"observedSerial":${view.path("serial")},"evidenceReference":"actual RMA received"}"""
        val endpoint = "/api/v1/warehouse/rma-handovers/${view.path("id").asString()}/acknowledge"
        val received = request("POST", endpoint, actor, ack, "own-rma-ack")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(200)
        assertThat(request("POST", endpoint, actor, ack, "own-rma-ack").contentAsString).isEqualTo(received.contentAsString)
        val held = get(path, actor).path("items").single().path("handover")
        assertThat(held.path("state").asString()).isEqualTo("RECEIVED")
        assertThat(get("$root/${case.work}/custody", actor).path("totalElements").asLong()).isZero()
        val intent = """{"expectedRevision":${context.path("workOrderRevision").asLong()},"assetId":${held.path("stockIdentityId")},"issueLineId":null,
            "purpose":"RETURN_CUSTOMER_RMA","ownershipMode":"SALE","previousAssignmentId":${held.path("originalAssignmentId")},"repairCaseId":${held.path("repairCaseId")}}"""
        val authorized = request("POST", "/api/work-orders/${case.work}/assets/authorize", actor, intent, "own-rma-authorization")
        assertThat(authorized.status).withFailMessage(authorized.contentAsString).isEqualTo(200)
        val permit = mapper.readTree(authorized.contentAsString)
        val installation = """{"authorizationId":${permit.path("authorizationId")},"expectedRevision":${permit.path("revision").asLong()},"topology":null}"""
        val installed = request("POST", "/api/customers/${case.customer}/assets/install", actor, installation, "own-rma-install")
        assertThat(installed.status).withFailMessage(installed.contentAsString).isEqualTo(201)
        assertThat(request("POST", "/api/customers/${case.customer}/assets/install", actor, installation, "own-rma-install").contentAsString).isEqualTo(installed.contentAsString)
        assertThat(get(path, actor).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "$path/${view.path("id").asString()}", actor).status).isEqualTo(404)
        fixture(case.repair.token).transaction {
            assertThat(scalar("SELECT concat_ws('|',legal_owner,status) FROM inventory_serialized_asset WHERE id='${case.repair.asset}'")).isEqualTo("CUSTOMER|CUSTOMER_INSTALLED")
            assertThat(scalar("SELECT count(*) FROM inventory_asset_assignment WHERE asset_id='${case.repair.asset}' AND ended_at IS NULL")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE stock_identity_id='${case.repair.asset}' AND status='AVAILABLE' AND quantity_base>0")).isEqualTo("0")
        }
    }

    @Test fun `RMA current location fences apply before paging and other actors cannot select receiver custody`() {
        val case = prepareRma()
        val outbound = dispatchRma(case)
        val root = "/api/v1/warehouse/my-materials"
        val path = "$root/${case.work}/rmas"
        val actor = case.receipt.receiver.first
        assertThat(get("$path?size=1", actor).path("totalElements").asLong()).isEqualTo(1)
        val other = technician(case.repair.token)
        assertThat(request("GET", path, other.first).status).isEqualTo(404)
        assertThat(request("GET", path, tenant()).status).isEqualTo(404)
        for (suffix in listOf("?actorId=${case.receipt.receiver.second}", "?page=0&page=1", "?size=101", "?serial=${case.repair.serial}"))
            assertThat(request("GET", "$path$suffix", actor).status).isEqualTo(400)
        assertThat(request("PUT", "/api/v1/warehouse/settings/scopes/${case.receipt.receiver.second}/${case.repair.returned.quarantine}", case.repair.token,
            """{"expectedRevision":1,"active":false}""").status).isEqualTo(200)
        assertThat(get("$path?size=1", actor).path("totalElements").asLong()).isZero()
        assertThat(request("GET", "$path/${outbound.path("id").asString()}", actor).status).isEqualTo(404)
        assertThat(get(root, actor).path("items").asSequence().map { it.path("id").asString() }.toList()).doesNotContain(case.work)
    }
}
