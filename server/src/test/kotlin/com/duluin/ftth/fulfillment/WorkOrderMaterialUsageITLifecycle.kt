package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

class WorkOrderMaterialUsageITLifecycle : MaterialUsageFixture() {
    @Test fun `current WO revision can report use after starting an already acknowledged job`() {
        val case = usageCase()
        val receipt = case.receipt
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/start", receipt.receiver.first).status).isEqualTo(200)
        val revision = summary(receipt.receiver.first, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        assertThat(revision).isGreaterThan(case.input.workOrderRevision)

        val response = use(case, case.input.copy(workOrderRevision = revision))

        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(usageAccounting(case)).startsWith("17500|82500|1|1|1|1|")
    }

    @Test fun `QA rejection and resubmission preserve consumed material and usage revision`() {
        val case = usageCase()
        val receipt = case.receipt
        val body = used(case)
        val before = usageAccounting(case)
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/start", receipt.receiver.first).status).isEqualTo(200)
        val packet = completionPacket(receipt)
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/complete", receipt.receiver.first, packet).status).isEqualTo(200)

        val rejected = request("POST", "/api/work-orders/${receipt.workOrder}/reject", receipt.stock.token, """{"reason":"Repeat field inspection"}""")

        assertThat(rejected.status).withFailMessage(rejected.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(rejected.contentAsString).path("approvalStatus").asString()).isEqualTo("REJECTED")
        assertThat(usageAccounting(case)).isEqualTo(before)
        assertThat(use(case).contentAsString).isEqualTo(body)
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/complete", receipt.receiver.first, packet).status).isEqualTo(200)
        assertThat(usageAccounting(case)).isEqualTo(before)
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT max(use_revision) FROM inventory_usage_snapshot")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_fulfillment_effect")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM onu")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement WHERE kind IN ('RETURN','SCRAP','LOSS','DISPOSAL')")).isEqualTo("0")
        }
    }

    private fun completionPacket(receipt: ReceiptCase): String {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)
        val artifacts = listOf("FAT", "OPTICAL_BEFORE", "OPTICAL_AFTER", "TECHNICIAN_SIGNATURE", "LOCATION").map { kind ->
            val response = mvc.perform(multipart("/api/work-orders/${receipt.workOrder}/evidence")
                .file(MockMultipartFile("file", "$kind.png", MediaType.IMAGE_PNG_VALUE, png))
                .param("kind", kind).header("Authorization", "Bearer ${receipt.receiver.first}")).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            mapOf("kind" to kind, "revisionId" to mapper.readTree(response.contentAsString).path("revisionId").asString())
        }
        val proof = request("GET", "/api/work-orders/${receipt.workOrder}/proof-of-work", receipt.receiver.first)
        assertThat(proof.status).isEqualTo(200)
        return mapper.writeValueAsString(mapOf("proofRevision" to mapper.readTree(proof.contentAsString).path("revision").asString(),
            "artifacts" to artifacts, "resolutionNote" to "Measured work complete"))
    }
}
