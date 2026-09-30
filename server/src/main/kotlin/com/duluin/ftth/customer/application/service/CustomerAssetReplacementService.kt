package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.customer.*
import com.duluin.ftth.customer.adapter.outbound.persistence.CustomerAssetInstallationStore
import com.duluin.ftth.customer.adapter.outbound.persistence.CustomerAssetRetirementStore
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.inventory.AssetRemovalResult
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class CustomerAssetReplacementService(private val locks: CustomerFulfillmentLockApi, private val retirements: CustomerAssetRetirementStore,
    private val installations: CustomerAssetInstallationStore, private val network: NetworkApi, private val onus: OnuRepository) : CustomerAssetReplacementApi {
    override fun applyRemoval(result: AssetRemovalResult, topology: CustomerAssetTopology?): CustomerAssetChange {
        locks.lock(result.customerId, null)
        val retired = retirements.retire(result)
        val replacement = result.replacement?.let { consumption ->
            installations.find(consumption.operationId) ?: run {
                topology?.let {
                    if (!consumption.createsOnu || it.portNumber < 1 || it.installRxPowerDbm?.let { power -> !power.isFinite() || power !in -40.0..0.0 } == true)
                        throw ConflictException("INVALID_REPLACEMENT_TOPOLOGY")
                    network.assertOdpPortAssignable(it.odpId, it.portNumber, onus.findByOdpId(it.odpId).mapNotNullTo(HashSet()) { onu -> onu.odpPortNumber })
                }
                installations.append(consumption, topology)
            }
        }
        return CustomerAssetChange(result.operationId, retired, replacement)
    }
}
