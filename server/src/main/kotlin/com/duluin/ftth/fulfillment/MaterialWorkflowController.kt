package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/work-orders/{id}/materials")
class MaterialWorkflowController(private val workflow: MaterialWorkflowService) {
    @GetMapping
    fun summary(@PathVariable id: UUID) = workflow.summary(id)
    @GetMapping("/history")
    fun history(@PathVariable id: UUID, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "25") size: Int) =
        workflow.history(id, WarehousePageRequest(page, size))
    @PutMapping("/plan")
    fun plan(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(workflow.replacePlan(id, MaterialWorkflowJson.decode(body, MaterialPlanningRequest::class.java), key))
    @PostMapping("/submit-request")
    fun submit(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(workflow.submitRequest(id, MaterialWorkflowJson.decode(body, MaterialPlanCommand::class.java), key))
    @PostMapping("/reserve")
    fun reserve(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(workflow.reserve(id, MaterialWorkflowJson.decode(body, MaterialPlanCommand::class.java), key))
    @PostMapping("/release")
    fun release(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(workflow.release(id, MaterialWorkflowJson.decode(body, MaterialPlanCommand::class.java), key))
    @PostMapping("/pick", "/dispatch", "/acknowledge", "/report-use", "/return", "/reallocate", "/settlement")
    fun unavailable(@PathVariable id: UUID): Nothing {
        workflow.summary(id)
        throw WarehouseContractException(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Material transition not available in task13"))
    }
    private fun response(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
