package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.InventoryTenantCutoverApi
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseOperationClass
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentQuery
import com.duluin.ftth.inventory.adapter.outbound.persistence.ReplenishmentStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryAccess
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.network.SiteReferenceApi
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WarehouseReplenishmentScan(private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi,
    private val masters: WarehouseMasterStore, private val query: ReplenishmentQuery, private val store: ReplenishmentStore,
    private val sites: SiteReferenceApi, private val evaluation: ReplenishmentEvaluator) {
    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    fun batch(): Int {
        store.deadline()
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK).assertHeld()
        authority.lockForChange().assertHeld()
        masters.lockTopology()
        val scope = AuthorityScope.Unrestricted
        val access = WarehouseQueryAccess(scope, scope, sites.visibleAreas(scope), false, false)
        val ids = query.scanBatch()
        ids.forEach { evaluation.recompute(store.rule(it), access) }
        return ids.size
    }
}

@Component
@ConditionalOnProperty(name = ["ftth.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class WarehouseReplenishmentSchedule(private val tenants: TenantApi, private val scan: WarehouseReplenishmentScan) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.warehouse.replenishment-delay:PT5M}")
    fun scan() {
        tenants.findActiveTenantIds().forEach { tenant ->
            try { TenantContext.runAs(tenant) { scan.batch() } }
            catch (failure: Exception) {
                val code = if (failure is WarehouseContractException) failure.error.code.name else "REPLENISHMENT_SCAN_FAILURE"
                log.warn("warehouse_replenishment_scan_failed tenant={} code={}", tenant, code)
            }
        }
    }
}
