package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseTracking
import java.time.Instant
import java.util.UUID

enum class ReferenceReturnState { PENDING, RECEIVED, REJECTED }
data class ReferenceReturnInput(val warehouseId: UUID, val skuId: UUID, val lines: List<ReferenceTransferLine>, val reason: String)
data class ReferenceReturnDecision(val expectedRevision: Long, val received: Boolean, val notes: String = "")
data class ReferenceReturnLine(val stockIdentityId: UUID, val quantityBase: String, val serial: String?, val mac: String?)
data class ReferenceReturnView(val id: UUID, val revision: Long, val state: ReferenceReturnState, val technicianId: UUID,
    val technicianName: String, val sourceLocationId: UUID, val warehouseId: UUID, val warehouseName: String, val skuId: UUID,
    val skuName: String, val baseUnit: WarehouseBaseUnit, val tracking: WarehouseTracking, val quantityBase: String,
    val lines: List<ReferenceReturnLine>, val reason: String, val createdAt: Instant, val updatedAt: Instant,
    val reviewedBy: UUID? = null, val reviewerName: String? = null, val reviewNotes: String? = null, val movementId: UUID? = null)
data class ReferenceReturnEvent(val id: UUID, val revision: Long, val action: String, val actorName: String,
    val notes: String, val movementId: UUID?, val recordedAt: Instant)
data class ReferenceReturnDetail(val request: ReferenceReturnView, val timeline: List<ReferenceReturnEvent>)
data class ReferenceTechnicianReturnInput(val sourceWarehouseId: UUID, val warehouseId: UUID, val technicianId: UUID,
    val skuId: UUID, val lines: List<ReferenceTransferLine>, val notes: String)
