package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerAssetChange
import com.duluin.ftth.inventory.AssetRemovalResult
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository

@Repository
class AssetProvisioningOutbox(private val entityManager: EntityManager) {
    fun append(result: AssetRemovalResult, change: CustomerAssetChange) = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("""INSERT INTO fulfillment_asset_outbox(tenant_id,operation_id,customer_id,work_order_id,old_onu_id,new_onu_id)
            VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, result.operationId)
            query.setObject(3, result.customerId); query.setObject(4, result.workOrderId)
            query.setObject(5, change.retired.onuId); query.setObject(6, change.replacement?.onuId); query.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO fulfillment_asset_delivery(tenant_id,operation_id) VALUES (?,?) ON CONFLICT DO NOTHING").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, result.operationId); query.executeUpdate()
        }
    }
}
