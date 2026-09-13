package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import tools.jackson.databind.JsonNode

abstract class MaterialReworkFixture : WarehouseFulfillmentFixture() {
    protected data class ReworkCase(val usage: UsageCase, val originalUsage: String, val previousPlan: JsonNode, val input: JsonNode)

    protected fun reworkCase(quantity: String = "82500"): ReworkCase {
        val acknowledged = usageCase()
        val case = acknowledged.copy(input = acknowledged.input.copy(lines = acknowledged.input.lines.map { it.copy(quantityBase = quantity) }))
        val original = used(case)
        val receipt = case.receipt
        val previousPlan = summary(receipt.stock.token, receipt.workOrder).path("plan")
        completeJob(receipt.workOrder, receipt.receiver.first)
        val priorProof = fixture(receipt.stock.token).transaction { scalar("SELECT proof_of_work_hash FROM work_order WHERE id='${receipt.workOrder}'") }
        assertThat(request("POST", "/api/work-orders/${receipt.workOrder}/reject", receipt.stock.token,
            """{"reason":"Additional measured work is required"}""").status).isEqualTo(200)
        addReworkEvidence(case)
        val proof = mapper.readTree(request("GET", "/api/work-orders/${receipt.workOrder}/proof-of-work", receipt.receiver.first).contentAsString)
        val revision = summary(receipt.stock.token, receipt.workOrder).path("revisions").path("workOrderRevision").asLong()
        val input = mapper.readTree(mapper.writeValueAsString(mapOf("expectedRevision" to 1, "workOrderRevision" to revision,
            "previousPlanId" to previousPlan.path("id").asString(), "previousUsageId" to mapper.readTree(original).path("usageId").asString(),
            "expectedUsageRevision" to 1, "previousEvidenceRevision" to priorProof, "evidenceRevision" to proof.path("revision").asString(),
            "reason" to "Add ten metres without changing prior consumption", "deltas" to listOf(mapOf("skuId" to receipt.stock.cable,
                "quantityBase" to "10000", "baseUnit" to "MM", "continuousCut" to false)))))
        return ReworkCase(case, original, previousPlan, input)
    }

    protected fun addReworkEvidence(case: UsageCase, kind: String = "ODP") {
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4, 5)
        val result = mvc.perform(multipart("/api/work-orders/${case.receipt.workOrder}/evidence")
            .file(MockMultipartFile("file", "$kind.png", MediaType.IMAGE_PNG_VALUE, png))
            .param("kind", kind).header("Authorization", "Bearer ${case.receipt.receiver.first}")).andReturn().response
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(201)
    }

    protected fun rework(case: ReworkCase, input: JsonNode = case.input, key: String = "explicit-rework", token: String = case.usage.receipt.stock.token) =
        request("POST", "/api/work-orders/${case.usage.receipt.workOrder}/materials/rework", token, mapper.writeValueAsString(input), key)

    protected fun resubmit(case: ReworkCase) {
        val receipt = case.usage.receipt
        val proof = mapper.readTree(request("GET", "/api/work-orders/${receipt.workOrder}/proof-of-work", receipt.receiver.first).contentAsString)
        val packet = mapper.writeValueAsString(mapOf("proofRevision" to proof.path("revision").asString(),
            "artifacts" to proof.path("artifacts").asSequence().map { mapOf("kind" to it.path("kind").asString(), "revisionId" to it.path("revisionId").asString()) }.toList(),
            "resolutionNote" to "Rework resubmitted"))
        val result = request("POST", "/api/work-orders/${receipt.workOrder}/complete", receipt.receiver.first, packet)
        assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(200)
    }
}
