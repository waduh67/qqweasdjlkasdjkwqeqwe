package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import java.util.UUID

@Import(ReceiptRealStorage::class)
class WarehouseQueryITLotFilters : WarehouseReceiptHttpFixture() {
    @Autowired private lateinit var storage: ObjectStorage

    @Test fun `AV9-01 received interval survives later inspection split and partial putaway`() {
        val setup = setupReceipt()
        val receipt = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"DATE-REEL"}""")
        val receiptId = receipt.path("id").asString()
        transition(setup, receiptId, "receive", """{"expectedRevision":0}""")
        val fixture = fixture(setup.token)
        val lotId = fixture.transaction { scalar("SELECT id FROM inventory_lot") }
        val receivedAt = fixture.transaction { jdbc { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT received_at FROM inventory_lot WHERE id='$lotId'").use { rows -> rows.next(); rows.getTimestamp(1).toInstant() }
        } } }
        val until = fixture.transaction { jdbc { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("SELECT clock_timestamp()").use { rows -> rows.next(); rows.getTimestamp(1).toInstant() }
        } } }
        val from = receivedAt.minusSeconds(1)
        val range = "from=$from&until=$until"
        val upload = mvc.perform(multipart("/api/v1/warehouse/receipts/$receiptId/attachments")
            .file(MockMultipartFile("file", "proof.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf()))
            .param("expectedRevision", "1").header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", "Bearer ${setup.token}")).andReturn().response
        assertThat(upload.status).withFailMessage(upload.contentAsString).isEqualTo(201)
        val evidenceId = mapper.readTree(upload.contentAsString).path("id").asString()
        try {
            val line = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", setup.token).contentAsString).path("lines")[0]
            transition(setup, receiptId, "inspect", """{"expectedRevision":2,"lines":[{
                "lineId":"${line.path("id").asString()}","stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}",
                "baseUnit":"MM","acceptedBase":"900000","rejectedBase":"100000","evidenceId":"$evidenceId","reason":"Measured"}]}""")
            val inspected = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$receiptId", setup.token).contentAsString)
            val accepted = inspected.path("lines")[0].path("pieces").single { it.path("disposition").asString()=="ACCEPTED" }
            transition(setup, receiptId, "putaway", """{"expectedRevision":3,"destinationLocationId":"${setup.bin}","lines":[{
                "lineId":"${line.path("id").asString()}","stockIdentityId":"${accepted.path("stockIdentityId").asString()}","quantityBase":"450000","baseUnit":"MM"}]}""")
            fixture.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE quantity_base>0 AND updated_at<='$until'::timestamptz")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE quantity_base>0")).isEqualTo("3")
            }
            assertLotCount(setup.token, range, 1)
            assertLotCount(setup.token, "$range&locationId=${setup.bin}&status=AVAILABLE&condition=SERVICEABLE&owner=ISP&skuId=${setup.cable}", 1)
            assertLotCount(setup.token, "$range&condition=DAMAGED", 0)
            assertLotCount(setup.token, "$range&owner=CUSTOMER", 0)
            assertLotCount(setup.token, "from=${until.plusSeconds(1)}&until=${until.plusSeconds(2)}", 0)
            val timestamps = listOf(from.minusSeconds(1), receivedAt, until.plusSeconds(1))
            for (binTime in timestamps) {
                fixture.transaction {
                    sql("UPDATE inventory_balance_projection SET updated_at=CASE WHEN location_id='${setup.bin}' THEN '$binTime'::timestamptz WHEN quantity_base=100000 THEN '${timestamps[0]}'::timestamptz ELSE '${timestamps[1]}'::timestamptz END,revision=revision+1 WHERE quantity_base>0")
                }
                assertLotCount(setup.token, "$range&locationId=${setup.bin}&status=AVAILABLE", 1)
                val positions = mapper.readTree(request("GET", "/api/v1/warehouse/stock/positions?$range&locationId=${setup.bin}", setup.token).contentAsString)
                assertThat(positions.path("totalElements").asInt()).isEqualTo(if (binTime==receivedAt) 1 else 0)
            }
            val (viewer, viewerId) = user(setup.token, setOf("inventory.item.view"))
            val me = mapper.readTree(request("GET", "/api/me", viewer).contentAsString)
            assertThat(request("PUT", "/api/users/$viewerId/access", setup.token,
                mapper.writeValueAsString(mapOf("roleIds" to me.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to listOf(area(setup.token))))).status).isEqualTo(200)
            fixture.transaction {
                for (location in listOf(setup.source, setup.bin)) sql("INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch) VALUES ('${UUID.randomUUID()}','$tenant','$viewerId','$location','$viewerId',0)")
            }
            assertLotCount(viewer, range, 0)
            assertThat(request("GET", "/api/v1/warehouse/lots/$lotId?$range", viewer).status).isEqualTo(404)
            fixture.transaction { sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE quantity_base>0") }
            assertLotCount(setup.token, range, 0)
            assertLotCount(setup.token, "", 0)
        } finally { storage.delete("${fixture.tenant}/warehouse/receipts/$receiptId/$evidenceId") }
    }

    private fun assertLotCount(token: String, query: String, expected: Int) {
        val response = request("GET", "/api/v1/warehouse/lots?$query", token)
        println("AV9-01 query=$query status=${response.status} body=${response.contentAsString}")
        assertThat(response.status).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsString).path("totalElements").asInt()).describedAs(query).isEqualTo(expected)
    }
}
