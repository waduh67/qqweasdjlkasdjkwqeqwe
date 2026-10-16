package com.karuhun.ftth.fulfillment

import com.karuhun.ftth.customer.CustomerAssetApi
import com.karuhun.ftth.customer.CustomerAssetEpisode
import com.karuhun.ftth.customer.InstallCustomerAssetRequest
import com.karuhun.ftth.customer.CustomerObservationApi
import com.karuhun.ftth.customer.CustomerAssetTopology
import com.karuhun.ftth.inventory.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class CustomerAssetWorkflowService(private val inventory: InventoryDeploymentApi, private val customers: CustomerAssetApi,
    private val observations: CustomerObservationApi) {
    fun authorize(workOrderId: UUID, request: DeploymentIntentRequest, metadata: WarehouseMutationMetadata): DeploymentAuthorizationRef =
        inventory.authorize(workOrderId, request, metadata)
    fun install(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode =
        customers.install(customerId, request, metadata)

    fun installObserved(serial: String, customerId: UUID, request: InstallCustomerAssetRequest,
        metadata: WarehouseMutationMetadata): CustomerAssetEpisode =
        verifyObserved(serial, customers.install(customerId, request, metadata))

    fun installObservedAutomatically(serial: String, customerId: UUID, topology: CustomerAssetTopology?, operationKey: String): CustomerAssetEpisode {
        val authorization = inventory.pendingDiscoveryAuthorization(serial, customerId) ?: throw WarehouseContractException(
            WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "An issued deployment authorization is required"))
        return verifyObserved(serial, customers.installDiscoveredAsset(customerId, InstallCustomerAssetRequest(authorization, 0, topology),
            WarehouseMutationMetadata(operationKey)))
    }

    private fun verifyObserved(serial: String, episode: CustomerAssetEpisode): CustomerAssetEpisode {
        if (episode.onuId == null || observations.currentEpisode(serial)?.onu?.id != episode.onuId)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Observed serial does not match issued asset"))
        return episode
    }

    fun acceptHandover(customerId: UUID, request: AcceptAssetHandoverRequest, metadata: WarehouseMutationMetadata): AssetAssignmentRef {
        val result = inventory.acceptHandover(request, metadata)
        if (result.customerId != customerId) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Customer assignment mismatch"))
        return result
    }
}
