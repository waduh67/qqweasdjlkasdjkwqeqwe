package com.duluin.ftth.fulfillment

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.WorkOrderMaterialContextApi
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/work-orders/{id}/materials/workbench")
class MaterialWorkbenchController(private val workflow: MaterialWorkbenchService) {
    @GetMapping fun context(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<MaterialFieldContext> {
        if (parameters.isNotEmpty()) invalid()
        return response(workflow.context(id))
    }
    @GetMapping("/custody") fun custody(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(workflow.custody(id, page(parameters)))
    @GetMapping("/usage") fun usage(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(workflow.usage(id, page(parameters)))
    private fun page(parameters: MultiValueMap<String, String>): WarehousePageRequest {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, default: Int) = parameters[key]?.single()?.let { it.toIntOrNull() ?: invalid() } ?: default
        return WarehousePageRequest(number("page", 0), number("size", 25))
    }
    private fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid material workbench filter"))
    private fun <T : Any> response(value: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(value)
}

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialWorkbenchService(private val authority: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val workOrders: WorkOrderMaterialContextApi, private val inventory: InventoryMaterialWorkbenchApi) {
    fun context(id: UUID) = inventory.context(locked(id))
    fun custody(id: UUID, page: WarehousePageRequest) = inventory.custody(locked(id), page)
    fun usage(id: UUID, page: WarehousePageRequest) = inventory.usage(locked(id), page)
    private fun locked(id: UUID): MaterialPlanningContext {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        val workOrder = workOrders.read(id)
        return MaterialPlanningContext(id, workOrder.code, workOrder.workType, workOrder.action.name, workOrder.workOrderRevision,
            workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
    }
}
