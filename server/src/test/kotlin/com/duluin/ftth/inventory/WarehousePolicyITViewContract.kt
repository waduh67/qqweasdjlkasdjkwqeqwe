package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.inbound.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class WarehousePolicyITViewContract {
    private val mapper = jacksonObjectMapper()
    @Test fun `operator response type cannot serialize fields reserved for approval or cost viewers`() {
        val view: WarehouseEvaluationView = WarehouseOperationEvaluation("APPROVAL_REQUIRED", UUID.randomUUID(), 3, "REQUEST_APPROVAL", "Request approval")
        assertThat(mapper.valueToTree<tools.jackson.databind.JsonNode>(view).properties().map { it.key }.toSet())
            .containsExactlyInAnyOrder("code", "sourceDocumentId", "sourceRevision", "requiredAction", "message")
    }
    @Test fun `direct candidate response contains no delegation placeholders`() {
        val view: WarehouseEvaluationApprover = WarehouseDirectApprover(UUID.randomUUID())
        assertThat(mapper.valueToTree<tools.jackson.databind.JsonNode>(view).properties().map { it.key }).containsExactly("userId")
    }
}
