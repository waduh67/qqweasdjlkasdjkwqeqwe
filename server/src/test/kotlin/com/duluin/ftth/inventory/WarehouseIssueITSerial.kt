package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

class WarehouseIssueITSerial : WarehouseIssueFixture() {
    @Test fun `ten exact serials remain in warehouse on pick and dispatch once into transit`() {
        val setup = issuedSetup(serials = 10)
        val body = pickBody(setup)
        val original = fixture(setup.stock.token).transaction { scalar("SELECT count(*) FROM inventory_movement_leg") }
        val picked = issueRequest(setup, "pick", body, "ten-serials")
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        assertThat(issue.path("lines").size()).isEqualTo(10)
        fixture(setup.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement_leg")).isEqualTo(original)
            assertThat(scalar("SELECT sum(reserved_picked_base) FROM inventory_reservation")).isEqualTo("10")
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset WHERE status='AVAILABLE' AND location_id='${setup.stock.bin}'")).isEqualTo("10")
        }
        val dispatchBody = transitionBody(setup, issue)
        val dispatched = issueRequest(setup, "dispatch", dispatchBody, "dispatch-ten")
        assertThat(dispatched.status).withFailMessage(dispatched.contentAsString).isEqualTo(200)
        assertThat(issueRequest(setup, "dispatch", dispatchBody, "dispatch-ten").contentAsString).isEqualTo(dispatched.contentAsString)
        assertThat(issueRequest(setup, "dispatch", dispatchBody).status).isEqualTo(409)
        fixture(setup.stock.token).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_serialized_asset WHERE status='IN_TRANSIT' AND custody_owner_kind<>'TECHNICIAN'")).isEqualTo("10")
            assertThat(scalar("SELECT count(*) FROM inventory_reservation WHERE state='DISPATCHED' AND reserved_picked_base=0 AND reserved_unpicked_base=0")).isEqualTo("10")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind='ISSUE'")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_document WHERE kind='ISSUE' AND state='RECEIVED'")).isEqualTo("0")
        }
    }

    @Test fun `scanner duplicate wrong identity wrong unit overpick and client authority errors perform zero writes`() {
        val setup = issuedSetup(serials = 1)
        val body = pickBody(setup)
        val before = fixture(setup.stock.token).transaction { counts() }
        for (mode in listOf("SCAN", "IDENTITY", "DUPLICATE", "UNIT", "QUANTITY", "RESERVATION", "REVISION", "ACTOR", "RECEIVER", "SUBSTITUTION")) {
            val payload = mapper.readTree(body) as ObjectNode
            val lines = payload.path("lines") as ArrayNode
            val line = lines[0] as ObjectNode
            when (mode) {
                "SCAN" -> line.put("scan", "WRONG-SERIAL")
                "IDENTITY" -> line.put("stockIdentityId", UUID.randomUUID().toString())
                "DUPLICATE" -> lines.add(line)
                "UNIT" -> line.put("baseUnit", "MM")
                "QUANTITY" -> line.put("quantityBase", "2")
                "RESERVATION" -> line.put("reservationId", UUID.randomUUID().toString())
                "REVISION" -> line.put("expectedRevision", 999)
                "ACTOR" -> payload.put("actorId", UUID.randomUUID().toString())
                "RECEIVER" -> payload.put("receiverId", UUID.randomUUID().toString())
                "SUBSTITUTION" -> line.put("skuId", setup.stock.cable)
            }
            val rejected = issueRequest(setup, "pick", mapper.writeValueAsString(payload))
            assertThat(rejected.status).withFailMessage("$mode: ${rejected.contentAsString}").isIn(400, 404, 409)
            fixture(setup.stock.token).transaction { assertThat(counts()).isEqualTo(before) }
        }
        val payload = mapper.readTree(body) as ObjectNode
        (payload.path("lines")[0] as ObjectNode).put("scan", " serial-1 ")
        val accepted = issueRequest(setup, "pick", mapper.writeValueAsString(payload))
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
    }

    @Test fun `partial cable pick requires explicit partial dispatch and retains quantitative demand`() {
        val setup = issuedSetup(requested = "200000")
        val picked = issueRequest(setup, "pick", pickBody(setup, "100000"))
        assertThat(picked.status).withFailMessage(picked.contentAsString).isEqualTo(200)
        val issue = mapper.readTree(picked.contentAsString)
        val denied = issueRequest(setup, "dispatch", transitionBody(setup, issue))
        assertThat(denied.status).withFailMessage(denied.contentAsString).isEqualTo(409)
        val accepted = issueRequest(setup, "dispatch", transitionBody(setup, issue, true))
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val totals = summary(setup.stock.token, setup.workOrder)
        assertThat(totals.path("demandState").asString()).isEqualTo("PART_ISSUED")
        assertThat(totals.path("lines")[0].path("issuedBase").asString()).isEqualTo("100000")
        assertThat(totals.path("lines")[0].path("reservedUnpickedBase").asString()).isEqualTo("100000")
        assertThat(totals.path("lines")[0].path("backorderBase").asString()).isEqualTo("0")
    }
}
