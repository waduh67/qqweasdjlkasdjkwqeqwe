package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityFence
import java.time.Instant
import java.util.UUID

interface InventoryCustomerRmaApi {
    fun dispatch(returnId: UUID, request: CustomerRmaDispatch, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun acknowledge(id: UUID, request: CustomerRmaReceipt, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun get(id: UUID): CustomerRmaHandover
    fun details(id: UUID): CustomerRmaHandoverDetails
    fun workOrder(returnId: UUID, workOrderId: UUID): CustomerRmaWorkOrder
}

data class CustomerRmaDispatch(val expectedRevision: Long, val workOrderId: UUID, val workOrderRevision: Long,
    val technicianId: UUID, val transitLocationId: UUID, val technicianLocationId: UUID,
    val observedSerial: String, val evidenceReference: String)
data class CustomerRmaReceipt(val expectedRevision: Long, val observedSerial: String, val evidenceReference: String)
enum class CustomerRmaHandoverState { DRAFT, DISPATCHED, RECEIVED }
data class CustomerRmaHandover(val id: UUID, val returnId: UUID, val repairCaseId: UUID,
    val originalAssignmentId: UUID, val customerId: UUID, val workOrderId: UUID, val workOrderRevision: Long,
    val technicianId: UUID, val stockIdentityId: UUID, val skuId: UUID, val serial: String,
    val sourceLocationId: UUID, val transitLocationId: UUID, val technicianLocationId: UUID,
    val legalOwner: AssetLegalOwner, val revision: Long, val state: CustomerRmaHandoverState,
    val locationId: UUID, val createdBy: UUID, val recordedAt: Instant)
data class CustomerRmaPersonRef(val id: UUID, val name: String)
data class CustomerRmaWorkOrder(val id: UUID, val code: String, val title: String, val customerId: UUID,
    val revision: Long, val technicians: List<CustomerRmaPersonRef>)
data class CustomerRmaHandoverDetails(val handover: CustomerRmaHandover, val workOrderCode: String, val workOrderTitle: String,
    val senderName: String?, val technicianName: String?, val locations: List<WarehouseReturnNamedRef>)

interface InventoryRmaWorkOrderPort {
    fun lock(workOrderId: UUID, revision: Long, customerId: UUID, technicianId: UUID, authority: AuthorityFence, requireCurrent: Boolean = true)
    fun read(workOrderId: UUID, customerId: UUID, authority: AuthorityFence, activeOnly: Boolean): CustomerRmaWorkOrder
}
