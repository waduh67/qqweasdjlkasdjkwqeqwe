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
@RequestMapping("/api/work-orders/{id}/materials/handover-workbench")
class MaterialHandoverWorkbenchController(private val service: MaterialHandoverWorkbenchService) {
    @GetMapping("/sources") fun sources(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.sources(id, page(parameters)))
    @GetMapping("/targets") fun targets(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.targets(id, page(parameters)))
    @GetMapping("/pending") fun pending(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.pending(id, page(parameters)))
    @GetMapping("/pending/{authorization}") fun grant(@PathVariable id: UUID, @PathVariable authorization: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<MaterialHandoverGrant> {
        if (parameters.isNotEmpty()) invalid()
        return response(service.grant(id, authorization))
    }
    private fun page(parameters: MultiValueMap<String, String>): WarehousePageRequest {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, default: Int) = parameters[key]?.single()?.let { it.toIntOrNull() ?: invalid() } ?: default
        return WarehousePageRequest(number("page", 0), number("size", 25))
    }
    private fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid material handover filter"))
    private fun <T : Any> response(value: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(value)
}

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialHandoverWorkbenchService(private val authority: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val workOrders: WorkOrderMaterialContextApi, private val inventory: InventoryMaterialHandoverWorkbenchApi) {
    fun sources(id: UUID, page: WarehousePageRequest) = inventory.sources(locked(id), page)
    fun targets(id: UUID, page: WarehousePageRequest) = inventory.targets(locked(id), page)
    fun pending(id: UUID, page: WarehousePageRequest) = inventory.pending(locked(id), page)
    fun grant(id: UUID, authorization: UUID) = inventory.pending(locked(id), WarehousePageRequest(0, 1), authorization).items.singleOrNull()
        ?: throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Pending handover is no longer visible"))
    private fun locked(id: UUID): MaterialPlanningContext {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        val workOrder = workOrders.lockForCustody(id, current.fence).material
        return MaterialPlanningContext(id, workOrder.code, workOrder.workType, workOrder.action.name, workOrder.workOrderRevision,
            workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
    }
}
