package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.*
import java.util.UUID

interface InventoryApprovalApi {
    fun request(command: CreateInventoryApproval): InventoryApprovalRequest
    fun decide(approvalId: UUID, command: DecideInventoryApproval): InventoryApprovalRequest
    fun get(approvalId: UUID): InventoryApprovalRequest?
    fun effects(tenantId: UUID): List<InventoryApprovalEffect>
}

class InventoryApprovalApiAdapter(private val service: InventoryApprovalService) : InventoryApprovalApi {
    override fun request(command: CreateInventoryApproval) = service.request(command)
    override fun decide(approvalId: UUID, command: DecideInventoryApproval) = service.decide(approvalId, command)
    override fun get(approvalId: UUID) = service.get(approvalId)
    override fun effects(tenantId: UUID) = service.effects(tenantId)
}

data class InventoryApprovalDecisionEvent(
    val tenantId: UUID,
    val approvalId: UUID,
    val type: InventoryApprovalType,
    val status: InventoryApprovalStatus,
    val movementId: UUID?,
    val operationKey: String,
)
