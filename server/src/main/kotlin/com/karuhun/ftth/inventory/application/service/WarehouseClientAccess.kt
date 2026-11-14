package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.adapter.outbound.persistence.WarehouseClientStore
import com.karuhun.ftth.inventory.application.port.inbound.WarehouseClientDocument
import com.karuhun.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Component

@Component
class WarehouseClientAccess(private val requests: ReferenceRequestService,private val store: WarehouseClientStore) {
    internal fun read(permission: String): ReferenceRequestService.Access = requests.readAccess().also { requests.permission(it.current,permission) }
    internal fun write(permission: String,key: String): ReferenceRequestService.Access = requests.access(key).also { requests.permission(it.current,permission) }
    internal fun <T: WarehouseClientDocument> authorize(view: T,access: ReferenceRequestService.Access): T = view.also { requests.warehouse(it.warehouseId,access) }
    internal fun <T: WarehouseClientDocument> replay(kind: String,action: String,key: String,canonical: WarehouseCanonicalPayload,
        access: ReferenceRequestService.Access,type: Class<T>): WarehouseOperationReceipt? {
        val prior=store.replay(kind,action,key) ?: return null
        requests.permission(access.current,prior.permission)
        if(prior.actorId!=access.current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
        if(prior.hash!=canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        if(prior.epoch!=access.cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
        authorize(store.get(kind,prior.resourceId,type),access)
        return prior.receipt
    }
    internal fun revision(view: WarehouseClientDocument,revision: Long) { if(view.revision!=revision) masterFailure(WarehouseErrorCode.STALE_REVISION) }
    internal fun pagination(page: Int,size: Int,search: String) { if(page<0 || size !in 1..100 || search.length>200) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST) }
}
