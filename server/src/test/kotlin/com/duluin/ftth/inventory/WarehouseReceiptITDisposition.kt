package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

@Import(ReceiptRealStorage::class)
class WarehouseReceiptITDisposition : WarehouseReceiptHttpFixture() {
    @Autowired private lateinit var storage: ObjectStorage
    @Test fun `partial inspected cable and ten serials become available only through accepted putaway`() {
        val setup = setupReceipt()
        val serials = (1..10).joinToString(",") { """{"serial":" onu-$it "}""" }
        val draft = draft(setup, """{"skuId":"${setup.cable}","quantityBase":"1000000","lotCode":"R1","cost":{"totalMinor":"500000","currency":"IDR"}},
            {"skuId":"${setup.onu}","quantityBase":"10","serials":[$serials]}""")
        val id = draft.path("id").asString()
        transition(setup, id, "receive", """{"expectedRevision":0}""")
        val upload = mvc.perform(multipart("/api/v1/warehouse/receipts/$id/attachments")
            .file(MockMultipartFile("file", "evidence.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf()))
            .param("expectedRevision", "1").header("Idempotency-Key", "inspection-proof")
            .header("Authorization", "Bearer ${setup.token}")).andReturn().response
        assertThat(upload.status).isEqualTo(201)
        val evidenceId = mapper.readTree(upload.contentAsString).path("id").asString()
        val database = fixture(setup.token)
        val objectKey = "${database.tenant}/warehouse/receipts/$id/$evidenceId"
        try {
            val detail = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString)
            val decisions = detail.path("lines").mapIndexed { index, line ->
                val accepted = if (index == 0) "900000" else if (index <= 8) "1" else "0"
                val rejected = if (index == 0) "100000" else if (index <= 8) "0" else "1"
                """{"lineId":"${line.path("id").asString()}","stockIdentityId":"${line.path("pieces")[0].path("stockIdentityId").asString()}",
                    "baseUnit":"${line.path("baseUnit").asString()}","acceptedBase":"$accepted","rejectedBase":"$rejected",
                    "evidenceId":"$evidenceId","reason":"Measured and tested","rejectedDisposition":"SUPPLIER_RETURN"}"""
            }.joinToString(",")
            val inspectBody = """{"expectedRevision":2,"lines":[$decisions]}"""
            transition(setup, id, "inspect", inspectBody, "inspect-key")
            database.transaction { assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE status='AVAILABLE'")).isEqualTo("0") }
            val inspected = mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id", setup.token).contentAsString)
            assertThat(inspected.path("inspections").size()).isEqualTo(11)
            assertThat(inspected.path("lines")[0].path("acceptedBase").asString()).isEqualTo("900000")
            assertThat(inspected.path("lines")[0].path("rejectedBase").asString()).isEqualTo("100000")
            val placements = inspected.path("lines").flatMap { line -> line.path("pieces").filter { it.path("disposition").asString() == "ACCEPTED" }.map { piece ->
                """{"lineId":"${line.path("id").asString()}","stockIdentityId":"${piece.path("stockIdentityId").asString()}",
                    "quantityBase":"${piece.path("quantityBase").asString()}","baseUnit":"${line.path("baseUnit").asString()}"}"""
            } }.joinToString(",")
            val putawayBody = """{"expectedRevision":3,"destinationLocationId":"${setup.bin}","lines":[$placements]}"""
            val putaway = transition(setup, id, "putaway", putawayBody, "putaway-key")
            assertThat(putaway.path("state").asString()).isEqualTo("PUTAWAY")
            assertThat(transition(setup, id, "putaway", putawayBody, "putaway-key")).isEqualTo(putaway)
            database.transaction {
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE' AND base_unit='MM'")).isEqualTo("900000")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='AVAILABLE' AND base_unit='EA'")).isEqualTo("8")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='QUARANTINE' AND base_unit='MM'")).isEqualTo("100000")
                assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection WHERE status='QUARANTINE' AND base_unit='EA'")).isEqualTo("2")
                assertThat(scalar("SELECT count(*) FROM inventory_inspection")).isEqualTo("11")
                assertThat(scalar("SELECT count(*) FROM inventory_receipt_disposition")).isEqualTo("12")
                assertThat(scalar("SELECT count(*) FROM inventory_segment WHERE parent_segment_id IS NOT NULL")).isEqualTo("2")
                assertThat(scalar("SELECT cost_total_minor::text||'/'||cost_basis_quantity_base::text FROM inventory_lot")).isEqualTo("500000/1000000")
            }
            assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/receipts/$id/history", setup.token).contentAsString).size()).isEqualTo(5)
        } finally { storage.delete(objectKey) }
    }
}
