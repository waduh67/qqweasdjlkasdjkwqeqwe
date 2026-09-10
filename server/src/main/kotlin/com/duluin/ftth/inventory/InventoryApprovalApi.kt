package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import java.util.UUID

interface InventoryApprovalApi {
    fun request(source: WarehouseSourceInput, key: String): WarehouseApprovalResponse
    fun decide(command: WarehouseApprovalDecisionInput, key: String): WarehouseApprovalResponse
    fun get(approvalId: UUID): WarehouseApprovalView
}

@org.springframework.stereotype.Service
class InventoryApprovalApiAdapter(private val service: DurableApprovalService) : InventoryApprovalApi {
    override fun request(source: WarehouseSourceInput, key: String) = service.request(source, key)
    override fun decide(command: WarehouseApprovalDecisionInput, key: String) = service.decide(command, key)
    override fun get(approvalId: UUID) = service.get(approvalId)
}

data class InventoryApprovalDecisionEvent(
    val tenantId: UUID,
    val approvalId: UUID,
    val type: InventoryApprovalType,
    val status: InventoryApprovalStatus,
    val movementId: UUID?,
    val operationKey: String,
)
