package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
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
    @PostMapping("/pick")
    fun pick(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(workflow.pick(id, MaterialWorkflowJson.decode(body, WarehousePickRequest::class.java), key))
    @PostMapping("/dispatch")
    fun dispatch(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(workflow.issueTransition(id, MaterialWorkflowJson.decode(body, WarehouseIssueRequest::class.java), key, true))
    @PostMapping("/unpick")
    fun unpick(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody body: String) =
        response(workflow.issueTransition(id, MaterialWorkflowJson.decode(body, WarehouseIssueRequest::class.java), key, false))
    @GetMapping("/issues/{issueId}/slip")
    fun slip(@PathVariable id: UUID, @PathVariable issueId: UUID) = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(workflow.issueSlip(id, issueId))
    @GetMapping("/issues")
    fun issues(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<String> {
        fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid issue filter"))
        if (parameters.any { (key, values) -> key !in setOf("page", "size", "state") || values.size != 1 || values.single().isBlank() }) invalid()
        fun number(key: String, default: Int): Int = parameters[key]?.single()?.let {
            if (!it.matches(Regex("[0-9]+"))) invalid()
            it.toIntOrNull() ?: invalid()
        } ?: default
        val state = parameters["state"]?.single()?.let { value -> WarehouseIssueState.entries.singleOrNull { it.name == value } ?: invalid() }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(workflow.issueList(id,
            WarehousePageRequest(number("page", 0), number("size", 25)), state))
    }
    private fun response(receipt: WarehouseOperationReceipt) = ResponseEntity.status(receipt.originalStatus)
        .contentType(MediaType.APPLICATION_JSON).body(receipt.originalBody)
}
