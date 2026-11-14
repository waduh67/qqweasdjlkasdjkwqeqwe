package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.adapter.outbound.persistence.WarehouseClientStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class WarehouseClientOverview(val purchasesPending: Long?,val loansAwaitingAdmin: Long?,val loansAwaitingManager: Long?,val activeLoans: Long?,val openWarranties: Long?)
@Service
@Transactional
class WarehouseClientOverviewService(private val requests: ReferenceRequestService,private val store: WarehouseClientStore) {
    fun overview(): WarehouseClientOverview {
        val access=requests.readAccess();val warehouses=requests.visibleWarehouses(access)
        fun counts(kind: String,permission: String) = if(requests.permits(access.current,permission)) store.stateCounts(kind,warehouses) else null
        val purchases=counts("PURCHASE","warehouse.request.view");val loans=counts("LOAN","warehouse.loan.view");val warranties=counts("WARRANTY","warehouse.warranty.view")
        return WarehouseClientOverview(purchases?.let { (it["ORDERED"] ?: 0)+(it["PARTIALLY_RECEIVED"] ?: 0) },loans?.getOrDefault("SUBMITTED",0),loans?.getOrDefault("MANAGER_REVIEW",0),
            loans?.let { (it["ACTIVE"] ?: 0)+(it["PARTIALLY_RETURNED"] ?: 0) },warranties?.let { (it["MASUK"] ?: 0)+(it["DIKIRIM"] ?: 0) })
    }
}
