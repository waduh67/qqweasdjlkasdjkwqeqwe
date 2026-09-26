package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseDraftExpiryRepairIT : WarehouseRepairFixture() {
    @ParameterizedTest @ValueSource(strings = ["LOAN", "SALE"])
    fun `due replacement receipt cannot receive or consume the vendor replacement`(mode: String) {
        val setup = repairSetup(mode)
        val old = setup.returned.old.installation.receipt
        val outbound = dispatchRepair(setup)
        val body = """{"expectedRevision":${outbound.path("revision").asLong()},"externalReference":"EXPIRY-EXCHANGE",
            "sourceLocationId":"${old.stock.source}","inspectionLocationId":"${setup.returned.quarantine}",
            "skuId":"${old.stock.onu}","serial":"EXPIRY-REPLACEMENT","evidenceReference":"vendor-exchange-proof"}"""
        val database = fixture(setup.token)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(1)
        val path = "${setup.path}/replacement-receipts"
        val original = request("POST", path, setup.token, body, "expiry-replacement")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path("receiptId").asString()
        val before = WarehouseDraftExpiryFacts.capture(database, id)
        clock.awaitDocument(id)
        val listed = request("GET", path, setup.token)
        assertThat(listed.status).withFailMessage(listed.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(listed.contentAsString).single().path("receiptState").asString()).isEqualTo("EXPIRED")
        val detail = request("GET", "/api/v1/warehouse/receipts/$id", setup.token)
        assertThat(detail.status).isEqualTo(200)
        assertThat(mapper.readTree(detail.contentAsString).path("state").asString()).isEqualTo("EXPIRED")
        WarehouseDraftExpiryFacts.rejected(request("POST", "/api/v1/warehouse/receipts/$id/receive", setup.token, """{"expectedRevision":0}"""))
        assertThat(request("POST", path, setup.token, body, "expiry-replacement").contentAsString).isEqualTo(original.contentAsString)
        WarehouseDraftExpiryFacts.expire(database, id)
        assertThat(WarehouseDraftExpiryFacts.capture(database, id)).isEqualTo(before)
        database.transaction { assertThat(scalar("SELECT count(*) FROM inventory_repair_replacement_receipt")).isEqualTo("0") }
        clock.policy(60)
        val fresh = request("POST", path, setup.token, body, "expiry-fresh")
        assertThat(fresh.status).withFailMessage(fresh.contentAsString).isEqualTo(201)
        val freshId = mapper.readTree(fresh.contentAsString).path("receiptId").asString()
        assertThat(freshId).isNotEqualTo(id)
        val received = request("POST", "/api/v1/warehouse/receipts/$freshId/receive", setup.token, """{"expectedRevision":0}""")
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(200)
        database.transaction {
            assertThat(scalar("SELECT receipt_id FROM inventory_repair_replacement_receipt")).isEqualTo(freshId)
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE document_id='$id'")).isEqualTo("0")
        }
    }

    @Test fun `due returned title request preserves customer ownership and original evidence`() {
        val returned = recoveredReturn("SALE")
        val token = returned.old.installation.receipt.stock.token
        val path = "/api/v1/warehouse/returns/${returned.id}"
        val body = """{"expectedRevision":${returned.revision},"reason":"Customer transfers returned device to ISP",
            "titleTransferReference":"signed-reacquisition","evidenceId":"${returned.old.signature}"}"""
        val database = fixture(token)
        val clock = WarehouseDraftClockFixture(database)
        clock.policy(1)
        val original = request("POST", "$path/reacquisition", token, body, "expiry-title")
        assertThat(original.status).withFailMessage(original.contentAsString).isEqualTo(201)
        val id = mapper.readTree(original.contentAsString).path("documentId").asString()
        val before = WarehouseDraftExpiryFacts.capture(database, id)
        clock.awaitDocument(id)
        val list = request("GET", "$path/reacquisition-requests", token)
        assertThat(list.status).withFailMessage(list.contentAsString).isEqualTo(200)
        val entry = mapper.readTree(list.contentAsString).path("items").single()
        assertThat(entry.path("state").asString()).isEqualTo("EXPIRED")
        assertThat(entry.path("evidenceId").asString()).isEqualTo(returned.old.signature.toString())
        WarehouseDraftExpiryFacts.rejected(request("POST", "/api/v1/warehouse/approvals/request", token,
            """{"sourceDocumentId":"$id","sourceRevision":0}"""))
        assertThat(request("POST", "$path/reacquisition", token, body, "expiry-title").contentAsString).isEqualTo(original.contentAsString)
        WarehouseDraftExpiryFacts.expire(database, id)
        assertThat(WarehouseDraftExpiryFacts.capture(database, id)).isEqualTo(before)
        assertThat(request("POST", "$path/reacquisition", token, body, "expiry-fresh").status).isEqualTo(201)
    }
}
