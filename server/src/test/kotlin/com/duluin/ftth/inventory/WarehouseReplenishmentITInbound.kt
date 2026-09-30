package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

class WarehouseReplenishmentITInbound : WarehouseReplenishmentInboundFixture() {
    @Test fun `generic confirmed partial transfer plus reservations is counted once`() {
        val scenario = inbound()
        val rule = createRule(scenario.token, scenario.fixture, inboundRule(scenario))
        val before = scenario.fixture.transaction { counts() }
        val suggestion = recompute(scenario.token, rule)
        assertThat(suggestion.path("availableBase").asString()).isEqualTo("40000")
        assertThat(suggestion.path("reservedBase").asString()).isEqualTo("20000")
        assertThat(suggestion.path("confirmedInboundBase").asString()).isEqualTo("40000")
        assertThat(suggestion.path("quantityBase").asString()).isEqualTo("75000")
        accept(scenario.token, suggestion)
        assertThat(scenario.fixture.transaction { counts() }).isEqualTo(before)
    }

    @Test fun `cancelling internal request does not erase confirmed receipt supply`() {
        val setup = setupReceipt()
        val body = """{"skuId":"${setup.cable}","locationId":"${setup.bin}","baseUnit":"MM","minimumBase":"90000",
            "maximumBase":"100000","targetBase":"100000","packageMultipleBase":"25000","leadTimeDays":7}"""
        val rule = command(setup.token, "rules", body)
        val accepted = accept(setup.token, recompute(setup.token, rule))
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"100000","lotCode":"SUPPLY"}""")
        val id = receipt.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val attachment = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
            .file(MockMultipartFile("file", "proof.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf())).param("expectedRevision", "1")
            .header("Authorization", "Bearer ${setup.token}").header("Idempotency-Key", "proof")).andReturn().response
        assertThat(attachment.status).isEqualTo(201)
        val proof = mapper.readTree(attachment.contentAsString).path("id").asString()
        val current = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        val line = current.path("id").asString()
        transition(setup, id, "inspect", """{"expectedRevision":2,"lines":[{"lineId":"$line",
            "stockIdentityId":"${current.path("pieces")[0].path("stockIdentityId").asString()}","baseUnit":"MM",
            "acceptedBase":"60000","rejectedBase":"40000","evidenceId":"$proof","reason":"Inspected supply"}]}""")
        val bound = command(setup.token, "requests/${accepted.path("id").asString()}/receiving-reference",
            """{"expectedRevision":${accepted.path("revision").asLong()},"documentId":"$id","documentRevision":3,"lineId":"$line"}""")
        val inspected = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString).path("lines")[0]
        val piece = inspected.path("pieces").single { it.path("disposition").asString() == "ACCEPTED" }
        val wrong = create("locations", setup.token, """{"code":"WRONG","name":"Other warehouse","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val wrongPutaway = request("POST", "/api/v1/warehouse/receipts/$id/putaway", setup.token,
            """{"expectedRevision":3,"destinationLocationId":"$wrong","lines":[{"lineId":"$line",
                "stockIdentityId":"${piece.path("stockIdentityId").asString()}","baseUnit":"MM","quantityBase":"20000"}]}""")
        assertThat(wrongPutaway.status).withFailMessage(wrongPutaway.contentAsString).isEqualTo(409)
        transition(setup, id, "putaway", """{"expectedRevision":3,"destinationLocationId":"${setup.bin}","lines":[{
            "lineId":"$line","stockIdentityId":"${piece.path("stockIdentityId").asString()}","baseUnit":"MM","quantityBase":"20000"}]}""")
        command(setup.token, "requests/${bound.path("id").asString()}/cancel", """{"expectedRevision":${bound.path("revision").asLong()}}""")
        val next = recompute(setup.token, rule)
        assertThat(next.path("availableBase").asString()).isEqualTo("20000")
        assertThat(next.path("confirmedInboundBase").asString()).isEqualTo("40000")
        assertThat(next.path("quantityBase").asString()).isEqualTo("50000")
    }

    @Test fun `pending quantity and rule snapshots cannot be forged through app role`() {
        val (token, fixture) = prepare()
        val request = recompute(token, createRule(token, fixture))
        org.assertj.core.api.Assertions.assertThatThrownBy {
            fixture.transaction { sql("UPDATE inventory_replenishment_request SET rule_snapshot=jsonb_set(rule_snapshot,'{baseUnit}','\"EA\"'),revision=revision+1 WHERE id='${request.path("id").asString()}'") }
        }.hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    }
}
