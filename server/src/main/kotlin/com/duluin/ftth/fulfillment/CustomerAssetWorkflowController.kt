package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.DeploymentIntentRequest
import com.duluin.ftth.inventory.AcceptAssetHandoverRequest
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class CustomerAssetWorkflowController(private val workflow: CustomerAssetWorkflowService) {
    @PostMapping("/api/work-orders/{id}/assets/authorize")
    fun authorize(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        workflow.authorize(id, MaterialWorkflowJson.decode(body, DeploymentIntentRequest::class.java), WarehouseMutationMetadata(key))

    @PostMapping("/api/customers/{id}/assets/handover")
    fun handover(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        workflow.acceptHandover(id, MaterialWorkflowJson.decode(body, AcceptAssetHandoverRequest::class.java), WarehouseMutationMetadata(key))
}
