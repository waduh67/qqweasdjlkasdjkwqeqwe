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
@RequestMapping("/api/v1/warehouse/my-materials")
class MyMaterialsController(private val service: MyMaterialsService) {
    @GetMapping fun jobs(@RequestParam parameters: MultiValueMap<String, String>) = response(service.jobs(page(parameters)))
    @GetMapping("/{id}") fun context(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>): ResponseEntity<MyMaterialContext> {
        if (parameters.isNotEmpty()) invalid()
        return response(service.context(id))
    }
    @GetMapping("/{id}/custody") fun custody(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.custody(id, page(parameters)))
    @GetMapping("/{id}/issues") fun issues(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.issues(id, page(parameters)))
    @GetMapping("/{id}/residuals") fun residuals(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.residuals(id, page(parameters)))
    @GetMapping("/{id}/return-locations") fun locations(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.locations(id, page(parameters)))
    @GetMapping("/{id}/custody/{source}") fun source(@PathVariable id: UUID, @PathVariable source: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.source(id, source).also { if (parameters.isNotEmpty()) invalid() })
    @GetMapping("/{id}/issues/{issue}") fun issue(@PathVariable id: UUID, @PathVariable issue: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.issue(id, issue).also { if (parameters.isNotEmpty()) invalid() })
    @GetMapping("/{id}/return-locations/{location}") fun location(@PathVariable id: UUID, @PathVariable location: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.location(id, location).also { if (parameters.isNotEmpty()) invalid() })
    private fun page(parameters: MultiValueMap<String, String>): WarehousePageRequest {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, default: Int) = parameters[key]?.single()?.let { it.toIntOrNull() ?: invalid() } ?: default
        return WarehousePageRequest(number("page", 0), number("size", 25))
    }
    private fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid own material filter"))
    private fun <T : Any> response(value: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(value)
}

data class MyMaterialContext(val id: UUID, val code: String, val workOrderRevision: Long, val technicalState: String,
    val qaState: String?, val currentAssignee: Boolean, val active: Boolean, val field: MaterialFieldContext?)

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MyMaterialsService(private val authority: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val workOrders: WorkOrderMaterialContextApi, private val inventory: InventoryMyMaterialsApi, private val workbench: InventoryMaterialWorkbenchApi) {
    fun jobs(page: WarehousePageRequest): WarehousePage<MyMaterialJob> {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        return inventory.jobs(page)
    }
    fun context(id: UUID): MyMaterialContext {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        val lifecycle = workOrders.lockForCustody(id, current.fence)
        val workOrder = lifecycle.material
        val context = MaterialPlanningContext(id, workOrder.code, workOrder.workType, workOrder.action.name, workOrder.workOrderRevision,
            workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
        inventory.authorize(context)
        val assigned = current.fence.identity.userId in workOrder.activeAssigneeIds
        return MyMaterialContext(id, workOrder.code, workOrder.workOrderRevision, lifecycle.technicalState, lifecycle.qaState,
            assigned, workOrder.active && !workOrder.cancelled, if (assigned) workbench.context(context) else null)
    }
    fun custody(id: UUID, page: WarehousePageRequest) = inventory.custody(locked(id), page)
    fun issues(id: UUID, page: WarehousePageRequest) = inventory.issues(locked(id), page)
    fun residuals(id: UUID, page: WarehousePageRequest) = inventory.residuals(locked(id), page)
    fun locations(id: UUID, page: WarehousePageRequest) = inventory.returnLocations(locked(id), page)
    fun source(id: UUID, source: UUID) = single(inventory.custody(locked(id), WarehousePageRequest(0, 1), source))
    fun issue(id: UUID, issue: UUID) = single(inventory.issues(locked(id), WarehousePageRequest(0, 1), issue))
    fun location(id: UUID, location: UUID) = single(inventory.returnLocations(locked(id), WarehousePageRequest(0, 1), location))
    private fun <T : Any> single(page: WarehousePage<T>) = page.items.singleOrNull()
        ?: throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Material reference is no longer visible"))
    private fun locked(id: UUID): MaterialPlanningContext {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        val workOrder = workOrders.lockForCustody(id, current.fence).material
        return MaterialPlanningContext(id, workOrder.code, workOrder.workType, workOrder.action.name, workOrder.workOrderRevision,
            workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
    }
}
