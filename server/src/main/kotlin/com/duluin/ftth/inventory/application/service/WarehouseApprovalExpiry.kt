package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseApprovalStore
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WarehouseApprovalExpiry(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val store: WarehouseApprovalStore, private val approvals: DurableApprovalService) {
    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    fun expireOne(): Boolean {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        authority.lockForChange().assertHeld()
        val id = store.due() ?: return false
        store.dueForSource(id).forEach { request -> approvals.terminate(store.get(request, true), WarehouseApprovalStatus.EXPIRED) }
        return true
    }
}

@Component
@ConditionalOnProperty(name = ["ftth.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class WarehouseApprovalExpirySchedule(private val tenants: TenantApi, private val expiry: WarehouseApprovalExpiry) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${ftth.warehouse.approval-expiry-delay:PT1M}")
    fun expire() {
        tenants.findActiveTenantIds().forEach { tenant ->
            try { TenantContext.runAs(tenant) { repeat(25) { if (!expiry.expireOne()) return@runAs } } }
            catch (failure: Exception) {
                val code = if (failure is WarehouseContractException) failure.error.code.name else "EXPIRY_FAILURE"
                log.warn("warehouse_approval_expiry_failed tenant={} code={}", tenant, code)
            }
        }
    }
}
