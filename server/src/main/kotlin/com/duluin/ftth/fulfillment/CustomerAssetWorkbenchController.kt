package com.duluin.ftth.fulfillment

import com.duluin.ftth.customer.CustomerAssetReadApi
import com.duluin.ftth.customer.CustomerAssetEpisodeState
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.WorkOrderAssetWorkbenchApi
import com.duluin.ftth.workorder.WorkOrderMaterialContextApi
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/customers/{id}/assets/workbench")
class CustomerAssetWorkbenchController(private val service: CustomerAssetWorkbenchService) {
    @GetMapping fun context(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.context(id).also { none(parameters) })
    @GetMapping("/history") fun history(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.history(id, page(parameters)))
    @GetMapping("/history/{assignment}") fun assignment(@PathVariable id: UUID, @PathVariable assignment: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.assignment(id, assignment).also { none(parameters) })
    @GetMapping("/jobs") fun jobs(@PathVariable id: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.jobs(id, page(parameters)))
    @GetMapping("/jobs/{workOrder}") fun job(@PathVariable id: UUID, @PathVariable workOrder: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.job(id, workOrder).also { none(parameters) })
    @GetMapping("/jobs/{workOrder}/sources") fun sources(@PathVariable id: UUID, @PathVariable workOrder: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.sources(id, workOrder, page(parameters)))
    @GetMapping("/jobs/{workOrder}/sources/{asset}") fun source(@PathVariable id: UUID, @PathVariable workOrder: UUID, @PathVariable asset: UUID, @RequestParam parameters: MultiValueMap<String, String>) = response(service.source(id, workOrder, asset).also { none(parameters) })
    private fun none(parameters: MultiValueMap<String, String>) { if (parameters.isNotEmpty()) invalid() }
    private fun page(parameters: MultiValueMap<String, String>): WarehousePageRequest {
        if (parameters.any { (key, values) -> key !in setOf("page", "size") || values.size != 1 || !values.single().matches(Regex("[0-9]+")) }) invalid()
        fun number(key: String, default: Int) = parameters[key]?.single()?.let { it.toIntOrNull() ?: invalid() } ?: default
        return WarehousePageRequest(number("page", 0), number("size", 25)).also { if (it.page < 0 || it.size !in 1..100) invalid() }
    }
    private fun invalid(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid customer asset filter"))
    private fun <T : Any> response(value: T) = ResponseEntity.ok().header("Cache-Control", "no-store").body(value)
}

data class CustomerAssetHistoryRow(val asset: CustomerAssetAssignmentView, val episode: CustomerAssetEpisodeState?)

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class CustomerAssetWorkbenchService(private val authorities: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val customers: CustomerAssetReadApi, private val workOrders: WorkOrderAssetWorkbenchApi,
    private val contexts: WorkOrderMaterialContextApi, private val inventory: InventoryAssetWorkbenchApi) {
    fun context(id: UUID) = locked(id).let { customers.authorize(id, it.authority) }
    fun jobs(id: UUID, page: WarehousePageRequest) = locked(id).let { workOrders.jobs(id, page, it.authority) }
    fun job(id: UUID, workOrder: UUID) = locked(id).let { workOrders.job(id, workOrder, it.authority) }
    fun history(id: UUID, page: WarehousePageRequest) = history(locked(id), page, null)
    fun assignment(id: UUID, assignment: UUID) = history(locked(id), WarehousePageRequest(0, 1), assignment).items.singleOrNull() ?: missing()
    private fun history(context: AssetCustomerReadContext, page: WarehousePageRequest, assignment: UUID?): WarehousePage<CustomerAssetHistoryRow> {
        val rows = inventory.history(context, page, assignment)
        val episodes = customers.episodes(context.customerId, rows.items.map { it.id }.toSet(), context.authority)
        return WarehousePage(rows.items.map { CustomerAssetHistoryRow(it, episodes[it.id]) }, rows.page, rows.size, rows.totalElements)
    }
    fun sources(id: UUID, workOrder: UUID, page: WarehousePageRequest) = sources(locked(id), workOrder, page, null)
    fun source(id: UUID, workOrder: UUID, asset: UUID) = sources(locked(id), workOrder, WarehousePageRequest(0, 1), asset).items.singleOrNull() ?: missing()
    private fun sources(context: AssetCustomerReadContext, id: UUID, page: WarehousePageRequest, asset: UUID?): WarehousePage<CustomerAssetSource> {
        val job = workOrders.job(context.customerId, id, context.authority)
        if (job.status !in setOf("ASSIGNED", "IN_PROGRESS") || job.workType !in setOf("PSB", "MIGRATION")) return WarehousePage(emptyList(), page.page, page.size, 0)
        val workOrder = contexts.lockForCustody(id, context.authority).material
        return inventory.sources(MaterialPlanningContext(id, workOrder.code, workOrder.workType, workOrder.action.name, workOrder.workOrderRevision,
            workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, context.authority, context.cutover), page, asset)
    }
    private fun locked(id: UUID): AssetCustomerReadContext {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val authority = authorities.lockCurrent().fence
        customers.authorize(id, authority)
        return AssetCustomerReadContext(id, authority, cutover)
    }
    private fun missing(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Customer asset reference is no longer visible"))
}
