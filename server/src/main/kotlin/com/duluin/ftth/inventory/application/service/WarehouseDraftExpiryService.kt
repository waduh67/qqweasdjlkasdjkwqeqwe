package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseApprovalStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseDraftLifetimeStore
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WarehouseDraftExpiryService(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val lifetime: WarehouseDraftLifetimeStore, private val approvalStore: WarehouseApprovalStore,
    private val approvals: DurableApprovalService) {
    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    fun expireOne(): Boolean {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        // No owner, stock or assignment is changed. The exclusive tenant authority
        // fence precedes the source and ordered approval locks, as for approval expiry.
        authority.lockForChange().assertHeld()
        lifetime.dueDocument()?.let { id ->
            if (lifetime.expireDocument(id)) lifetime.pendingApprovals(id).forEach {
                approvals.terminate(approvalStore.get(it, true), WarehouseApprovalStatus.EXPIRED, "DRAFT_EXPIRED")
            }
            return true
        }
        val plan = lifetime.duePlan() ?: return false
        lifetime.expirePlan(plan)
        return true
    }
}

@Component
@ConditionalOnProperty(name = ["ftth.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class WarehouseDraftExpirySchedule(private val tenants: TenantApi, private val expiry: WarehouseDraftExpiryService) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${ftth.warehouse.draft-expiry-delay:PT1M}")
    fun expire() {
        tenants.findActiveTenantIds().forEach { tenant ->
            try { TenantContext.runAs(tenant) { repeat(25) { if (!expiry.expireOne()) return@runAs } } }
            catch (failure: Exception) {
                val code = if (failure is WarehouseContractException) failure.error.code.name else "EXPIRY_FAILURE"
                log.warn("warehouse_draft_expiry_failed tenant={} code={}", tenant, code)
            }
        }
    }
}
