package com.duluin.ftth.fulfillment

import com.duluin.ftth.fulfillment.application.service.MaterialSettlementService
import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/work-orders/{id}/materials")
class MaterialSettlementController(private val settlement: MaterialSettlementService) {
    @GetMapping("/settlement")
    fun summary(@PathVariable id: UUID) = settlement.summary(id)

    @PostMapping("/settlement")
    fun close(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(settlement.close(id, MaterialWorkflowJson.decode(body, MaterialCloseRequest::class.java), key))

    @PostMapping("/return")
    fun dispatch(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(settlement.dispatch(id, MaterialWorkflowJson.decode(body, MaterialResidualRequest::class.java), key))

    @PostMapping("/residuals/acknowledge")
    fun acknowledge(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(settlement.acknowledge(id, MaterialWorkflowJson.decode(body, MaterialResidualAcknowledgement::class.java), key))

    @PostMapping("/handover/authorize")
    fun authorizeHandover(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(settlement.authorizeHandover(id, MaterialWorkflowJson.decode(body, MaterialResidualRequest::class.java), key))

    @PostMapping("/correct-use")
    fun correctUse(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(settlement.correctUse(id, MaterialWorkflowJson.decode(body, MaterialUsageDeltaRequest::class.java), key))

    @PostMapping("/handover", "/reallocate")
    fun handover(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String): ResponseEntity<String> {
        val request = MaterialWorkflowJson.decode(body, MaterialResidualRequest::class.java)
        if (request.authorizationId == null) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.APPROVAL_REQUIRED, "Dispatcher handover authorization required"))
        return response(settlement.dispatch(id, request, key))
    }

    private fun response(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
