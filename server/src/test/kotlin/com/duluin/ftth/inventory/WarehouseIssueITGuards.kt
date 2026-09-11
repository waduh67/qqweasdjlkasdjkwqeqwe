package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseIssueITGuards : WarehouseIssueFixture() {
    @Test fun `quarantined and customer owned identities cannot be picked from dirty state`() {
        for (mode in listOf("QUARANTINE", "CUSTOMER")) {
            val setup = issuedSetup(serials = 1)
            val body = pickBody(setup)
            fixture(setup.stock.token).transaction {
                when (mode) {
                    "QUARANTINE" -> sql("UPDATE inventory_balance_projection SET status='QUARANTINE',revision=revision+1 WHERE quantity_base>0")
                    "CUSTOMER" -> sql("UPDATE inventory_balance_projection SET legal_owner='CUSTOMER',revision=revision+1 WHERE quantity_base>0")
                }
            }
            val before = fixture(setup.stock.token).transaction { counts() }
            val result = issueRequest(setup, "pick", body)
            assertThat(result.status).withFailMessage("$mode: ${result.contentAsString}").isEqualTo(409)
            fixture(setup.stock.token).transaction { assertThat(counts()).isEqualTo(before) }
        }
    }

    @Test fun `terminal identity corruption is rejected before an active reservation can become spendable`() {
        val setup = issuedSetup(serials = 1)
        val before = fixture(setup.stock.token).transaction { counts() }
        assertThatThrownBy { fixture(setup.stock.token).transaction {
            sql("UPDATE inventory_segment SET state='RETIRED',revision=revision+1")
        } }.hasStackTraceContaining("terminal segment retains balance or encumbrance")
        fixture(setup.stock.token).transaction { assertThat(counts()).isEqualTo(before) }
    }

    @Test fun `foreign actor unauthorized picker and cancelled WO cannot issue and explicit unpick remains possible`() {
        val setup = issuedSetup(serials = 1)
        val body = pickBody(setup)
        val path = "/api/work-orders/${setup.workOrder}/materials/pick"
        val outsider = tenant()
        assertThat(request("POST", path, outsider, body).status).isEqualTo(404)
        val reader = user(setup.stock.token, setOf("workorder.order.view", "inventory.request.view"))
        assertThat(request("POST", path, reader.first, body).status).isIn(403, 404)
        val picked = issueRequest(setup, "pick", body)
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        assertThat(request("POST", "/api/work-orders/${setup.workOrder}/cancel", setup.stock.token, """{"reason":"Cancelled before handover"}""").status).isEqualTo(200)
        val transition = transitionBody(setup, issue)
        assertThat(issueRequest(setup, "dispatch", transition).status).isEqualTo(409)
        val unpick = issueRequest(setup, "unpick", transition)
        assertThat(unpick.status).withFailMessage(unpick.contentAsString).isEqualTo(200)
        fixture(setup.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("0")
            assertThat(scalar("SELECT sum(reserved_unpicked_base) FROM inventory_reservation")).isEqualTo("1")
        }
    }

    @Test fun `slip reprints immutable original labels and snapshot writes are rejected`() {
        val setup = issuedSetup(serials = 1)
        val picked = issueRequest(setup, "pick", pickBody(setup))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        val dispatched = issueRequest(setup, "dispatch", transitionBody(setup, issue))
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        assertThat(request("PUT", "/api/v1/warehouse/skus/${setup.stock.onu}", setup.stock.token,
            """{"expectedRevision":1,"code":"ONU","name":"Renamed after dispatch","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":false}""").status).isEqualTo(200)
        val slip = request("GET", "/api/work-orders/${setup.workOrder}/materials/issues/${issue.path("issueId").asString()}/slip", setup.stock.token)
        assertThat(slip.status).isEqualTo(200)
        assertThat(slip.contentAsString).isEqualTo(dispatched.contentAsString)
        assertThat(slip.contentAsString).doesNotContain("Renamed after dispatch")
        assertThatThrownBy { fixture(setup.stock.token).transaction {
            sql("UPDATE inventory_issue_snapshot SET sender_id='${UUID.randomUUID()}'")
        } }.isInstanceOf(Exception::class.java)
        assertThat(request("GET", "/api/work-orders/${setup.workOrder}/materials/issues/${issue.path("issueId").asString()}/slip", setup.stock.token).contentAsString)
            .isEqualTo(dispatched.contentAsString)
    }
}
