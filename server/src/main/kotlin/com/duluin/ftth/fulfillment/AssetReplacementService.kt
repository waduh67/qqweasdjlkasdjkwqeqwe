package com.duluin.ftth.fulfillment

import com.duluin.ftth.customer.*
import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class AssetReplacementService(private val inventory: InventoryAssetReplacementApi, private val customers: CustomerAssetReplacementApi,
    private val outbox: AssetProvisioningOutbox) {
    private val mapper = jacksonObjectMapper()
    fun replace(customerId: UUID, request: ReplaceCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetChange {
        val removed = inventory.replace(customerId, ReplacePhysicalAssetRequest(request.authorizationId, request.expectedRevision,
            request.expectedAssignmentRevision, request.expectedTitleRevision, request.evidenceId, mapper.writeValueAsString(request)), metadata)
        val changed = customers.applyRemoval(removed, request.topology)
        outbox.append(removed, changed)
        return changed
    }
    fun remove(customerId: UUID, request: RemovePhysicalAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetChange {
        val removed = inventory.remove(customerId, request, metadata)
        val changed = customers.applyRemoval(removed, null)
        outbox.append(removed, changed)
        return changed
    }
}
