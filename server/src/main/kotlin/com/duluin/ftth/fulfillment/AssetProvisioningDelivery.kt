package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Service
class AssetProvisioningDelivery(private val store: AssetProvisioningDeliveryStore, private val gateway: AssetProvisioningPort) {
    fun deliver(operationId: UUID): Boolean {
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        val claim = store.claim(operationId) ?: return false
        val outcome = gateway.apply(claim.work)
        return store.finish(claim, outcome)
    }
}

@Service
class AssetProvisioningWorker(private val tenants: TenantApi, private val store: AssetProvisioningDeliveryStore,
    private val delivery: AssetProvisioningDelivery) {
    @Scheduled(fixedDelayString = "\${ftth.provisioning.asset-dispatch-interval:PT10S}")
    fun drain() {
        tenants.findActiveTenantIds().forEach { tenant ->
            TenantContext.runAs(tenant) { store.pending().forEach { delivery.deliver(it) } }
        }
    }
}
