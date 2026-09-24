package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.adapter.outbound.persistence.SupplierReplacementStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseReturnStore
import com.duluin.ftth.inventory.AssetHandoverWorkOrderPort
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SupplierReplacementAdmission(private val replacements: SupplierReplacementStore, private val returns: WarehouseReturnStore,
    private val workOrders: AssetHandoverWorkOrderPort) : WarehouseApprovalSourceLock {
    override fun lock(sourceDocumentId: UUID, current: CurrentAuthority) {
        val source = replacements.forReceipt(sourceDocumentId) ?: return
        // WO authority precedes warehouse topology/document locks. The receipt
        // and source return are then held together through physical admission.
        workOrders.lockTitle(source.context.workOrderId, current.fence)
    }

    fun current(receiptId: UUID, current: CurrentAuthority): Boolean {
        val source = replacements.forReceipt(receiptId) ?: return true
        val returned = returns.get(source.view.returnId, true)
        return returned.view == source.returned.view &&
            replacements.lockAsset(source.view.originalAssetId) == source.context.assetRevision &&
            workOrders.lockTitle(source.context.workOrderId, current.fence) == source.workOrderRevision &&
            !replacements.consumed(source.view.repairCaseId) && returns.position(source.position.dimension) == source.position
    }
}
