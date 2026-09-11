package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthority
import java.time.Instant
import java.util.UUID

interface InventoryReservationApi {
    fun execute(documentId: UUID, action: ReservationAction, request: ReservationRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun replay(documentId: UUID, action: ReservationAction, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
    fun allocations(workOrderId: UUID): List<ReservationAllocation>
}

interface InventoryReservationWorkOrderPort {
    fun lock(id: UUID, current: CurrentAuthority?, expectedRevision: Long?): ReservationWorkOrder
}

data class ReservationWorkOrder(val revision: Long, val active: Boolean, val scheduledAt: Instant?, val scheduledEndAt: Instant?)
enum class ReservationAction { RESERVE, RELEASE, PICK, UNPICK, EXTEND, REALLOCATE }

data class ReservationRequest(
    val expectedRevision: Long,
    val workOrderRevision: Long,
    val planRevision: Long,
    val lines: List<ReservationSelection> = emptyList(),
    val allocations: List<ReservationAmount> = emptyList(),
    val reason: String? = null,
    val expiresAt: Instant? = null,
    val target: ReservationTarget? = null,
)
data class ReservationSelection(val demandLineId: UUID, val partialQuantityBase: String? = null, val stockIdentityId: UUID? = null)
data class ReservationAmount(val reservationId: UUID, val expectedRevision: Long, val quantityBase: String)
data class ReservationTarget(val documentId: UUID, val expectedRevision: Long, val workOrderRevision: Long, val planRevision: Long,
    val demandLineId: UUID)
data class ReservationAllocation(
    val allocationId: UUID, val reservationId: UUID, val reservationRevision: Long,
    val documentId: UUID, val documentRevision: Long, val demandLineId: UUID, val planLineId: UUID, val planRevision: Long,
    val workOrderId: UUID, val stockIdentityId: UUID, val lotId: UUID?, val skuId: UUID,
    val locationId: UUID, val originLineId: UUID, val originRevision: Long, val stockRevision: Long,
    val reservedUnpickedBase: String, val reservedPickedBase: String, val baseUnit: WarehouseBaseUnit,
    val state: String, val expiresAt: Instant, val customerId: UUID?, val actorId: UUID, val itemCategory: String,
    val demandSupply: ReservationDemandSupply,
)

data class ReservationDemandSupply(
    val snapshotId: UUID, val operationId: UUID, val documentRevision: Long, val planRevision: Long,
    val requestedBase: String, val reservedUnpickedBase: String, val reservedPickedBase: String,
    val totalReservedBase: String, val backorderBase: String, val baseUnit: WarehouseBaseUnit, val demandState: MaterialDemandState,
    val issuedBase: String = "0",
)
