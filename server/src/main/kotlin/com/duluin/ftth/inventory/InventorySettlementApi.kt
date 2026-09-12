package com.duluin.ftth.inventory

import java.util.UUID

interface InventorySettlementApi {
    fun freeze(context: MaterialPlanningContext): MaterialSettlementSource
    fun verify(context: MaterialPlanningContext, approval: MaterialSettlementApproval): MaterialVerificationReceipt
}

data class MaterialSettlementSource(
    val planId: UUID,
    val planRevision: Long,
    val materialMode: MaterialMode,
    val reason: String?,
    val usageId: UUID,
    val useRevision: Long,
    val usageHash: String,
    val usageBody: String,
    val documents: List<MaterialSourceRevision>,
)

data class MaterialSourceRevision(val id: UUID, val revision: Long)
data class MaterialSettlementApproval(val id: UUID, val hash: String, val source: MaterialSettlementSource)
data class MaterialVerificationReceipt(val approvalId: UUID, val usageId: UUID, val useRevision: Long, val result: String)
