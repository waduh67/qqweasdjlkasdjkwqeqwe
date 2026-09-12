package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart

abstract class WarehouseFulfillmentFixture : MaterialUsageFixture() {
    protected fun approvedCable(): UsageCase {
        val case = usageCase()
        used(case)
        completeJob(case.receipt.workOrder, case.receipt.receiver.first)
        val response = request("POST", "/api/work-orders/${case.receipt.workOrder}/approve", case.receipt.stock.token, "{}")
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        return case
    }

    protected fun frozenRequest(case: UsageCase): FulfillmentRequest = fixture(case.receipt.stock.token).transaction {
        scalar("SELECT request_payload FROM fulfillment_approval_snapshot WHERE work_order_id='${case.receipt.workOrder}'").decodeFulfillmentRequest()
    }

    protected fun physicalState(token: String): String = fixture(token).transaction {
        scalar("""SELECT concat_ws('|',
            (SELECT count(*) FROM inventory_movement),
            (SELECT count(*) FROM inventory_customer_material_fact),
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_customer_material_fact),
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='CONSUMED'),
            (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE status='ISSUED' AND custody_owner_kind='TECHNICIAN'))""")
    }

    protected fun completeJob(workOrder: String, token: String) {
        assertThat(request("POST", "/api/work-orders/$workOrder/start", token).status).isEqualTo(200)
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)
        val artifacts = listOf("FAT", "OPTICAL_BEFORE", "OPTICAL_AFTER", "TECHNICIAN_SIGNATURE", "LOCATION").map { kind ->
            val response = mvc.perform(multipart("/api/work-orders/$workOrder/evidence")
                .file(MockMultipartFile("file", "$kind.png", MediaType.IMAGE_PNG_VALUE, png))
                .param("kind", kind).header("Authorization", "Bearer $token")).andReturn().response
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
            mapOf("kind" to kind, "revisionId" to mapper.readTree(response.contentAsString).path("revisionId").asString())
        }
        val proof = request("GET", "/api/work-orders/$workOrder/proof-of-work", token)
        assertThat(proof.status).isEqualTo(200)
        val packet = mapper.writeValueAsString(mapOf("proofRevision" to mapper.readTree(proof.contentAsString).path("revision").asString(),
            "artifacts" to artifacts, "resolutionNote" to "Measured work complete"))
        val response = request("POST", "/api/work-orders/$workOrder/complete", token, packet)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
    }
}
