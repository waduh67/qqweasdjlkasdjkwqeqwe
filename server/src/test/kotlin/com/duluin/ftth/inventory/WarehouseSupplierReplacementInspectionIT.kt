package com.duluin.ftth.inventory

import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

@Import(ReceiptRealStorage::class)
class WarehouseSupplierReplacementInspectionIT : WarehouseSupplierReplacementFixture() {
    @Autowired private lateinit var storage: ObjectStorage

    @ParameterizedTest @ValueSource(strings = ["LOAN", "SALE"])
    fun `replacement putaway respects inspection and title and survives an atomic projection rebuild`(mode: String) {
        val repair = repairSetup(mode)
        val stock = repair.returned.old.installation.receipt.stock
        val sku = mapper.readTree(request("GET", "/api/v1/warehouse/skus/${stock.onu}", repair.token).contentAsString)
        val updated = request("PUT", "/api/v1/warehouse/skus/${stock.onu}", repair.token,
            """{"expectedRevision":${sku.path("revision").asLong()},"code":"${sku.path("code").asString()}","name":"ONU","tracking":"SERIAL","baseUnit":"EA","inspectionRequired":true}""")
        assertThat(updated.status).withFailMessage(updated.contentAsString).isEqualTo(200)
        val case = replacement(repair, dispatchRepair(repair), "INSPECTED-REPLACEMENT", "replacement-inspection")
        val received = receiveReplacement(case)
        assertThat(received.status).withFailMessage(received.contentAsString).isEqualTo(200)
        val asset = replacementAsset(case)
        val path = "/api/v1/warehouse/receipts/${case.receipt}"
        val line = mapper.readTree(request("GET", path, case.token).contentAsString).path("lines").single()
        fun putaway(revision: Int) = """{"expectedRevision":$revision,"destinationLocationId":"${stock.bin}","lines":[{"lineId":"${line.path("id").asString()}","stockIdentityId":"$asset","quantityBase":"1","baseUnit":"EA"}]}"""
        assertThat(request("POST", "$path/putaway", case.token, putaway(1)).status).isEqualTo(409)
        val upload = mvc.perform(multipart("$path/attachments")
            .file(MockMultipartFile("file", "vendor-inspection.pdf", "application/pdf", ReceiptEvidenceFixtures.pdf()))
            .param("expectedRevision", "1").header("Authorization", "Bearer ${case.token}")
            .header("Idempotency-Key", "replacement-inspection-proof")).andReturn().response
        assertThat(upload.status).withFailMessage(upload.contentAsString).isEqualTo(201)
        val proof = mapper.readTree(upload.contentAsString).path("id").asString()
        val database = fixture(case.token)
        try {
            val inspected = request("POST", "$path/inspect", case.token,
                """{"expectedRevision":2,"lines":[{"lineId":"${line.path("id").asString()}","stockIdentityId":"$asset","acceptedBase":"1","rejectedBase":"0","baseUnit":"EA","evidenceId":"$proof","reason":"Verified replacement serial and factory condition","rejectedDisposition":"QUARANTINE"}]}""", "inspect-replacement")
            assertThat(inspected.status).withFailMessage(inspected.contentAsString).isEqualTo(200)
            if (mode == "SALE") {
                assertThat(request("POST", "$path/putaway", case.token, putaway(3)).status).isEqualTo(409)
            } else {
                val placed = request("POST", "$path/putaway", case.token, putaway(3), "putaway-replacement")
                assertThat(placed.status).withFailMessage(placed.contentAsString).isEqualTo(200)
                assertThat(request("POST", "$path/putaway", case.token, putaway(3), "putaway-replacement").contentAsString)
                    .isEqualTo(placed.contentAsString)
            }
            val before = database.transaction { scalar("SELECT count(*) FROM inventory_movement") }
            assertThat(receiveReplacement(case).contentAsString).isEqualTo(received.contentAsString)
            database.transaction { context.getBean(WarehousePosting::class.java).rebuild(0) }
            database.transaction {
                assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(before)
                assertThat(scalar("SELECT concat_ws('|',legal_owner,status,condition) FROM inventory_serialized_asset WHERE id='$asset'"))
                    .isEqualTo(if (mode == "SALE") "CUSTOMER|QUARANTINE|QUARANTINE" else "ISP|AVAILABLE|SERVICEABLE")
                assertThat(scalar("SELECT origin_document_line_id::text FROM inventory_serialized_asset WHERE id='$asset'"))
                    .isEqualTo(line.path("id").asString())
                assertThat(scalar("SELECT custody_owner_kind FROM inventory_serialized_asset WHERE id='${repair.asset}'")).isEqualTo("REPAIR")
            }
        } finally { storage.delete("${database.tenant}/warehouse/receipts/${case.receipt}/$proof") }
    }
}
