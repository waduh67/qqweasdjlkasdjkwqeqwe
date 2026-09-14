package com.duluin.ftth.fulfillment

import com.duluin.ftth.customer.CustomerAssetApi
import com.duluin.ftth.customer.CustomerAssetEpisode
import com.duluin.ftth.customer.InstallCustomerAssetRequest
import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class CustomerAssetWorkflowService(private val inventory: InventoryDeploymentApi, private val customers: CustomerAssetApi) {
    fun authorize(workOrderId: UUID, request: DeploymentIntentRequest, metadata: WarehouseMutationMetadata): DeploymentAuthorizationRef =
        inventory.authorize(workOrderId, request, metadata)
    fun install(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode =
        customers.install(customerId, request, metadata)
}
