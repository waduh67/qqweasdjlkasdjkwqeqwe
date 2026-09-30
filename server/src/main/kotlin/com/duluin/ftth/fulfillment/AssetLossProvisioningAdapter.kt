package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.AssetLossClosure
import com.duluin.ftth.inventory.AssetLossProvisioningPort
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY)
class AssetLossProvisioningAdapter(private val entityManager: EntityManager) : AssetLossProvisioningPort {
    override fun enqueue(closure: AssetLossClosure, onuId: UUID?) = entityManager.unwrap(Session::class.java).doWork { connection ->
        connection.prepareStatement("""INSERT INTO fulfillment_asset_outbox(tenant_id,operation_id,customer_id,work_order_id,old_onu_id,new_onu_id)
            VALUES (?,?,?,?,?,NULL)""").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, closure.operationId)
            query.setObject(3, closure.customerId); query.setObject(4, closure.workOrderId); query.setObject(5, onuId)
            check(query.executeUpdate() == 1)
        }
        connection.prepareStatement("INSERT INTO fulfillment_asset_delivery(tenant_id,operation_id) VALUES (?,?)").use { query ->
            query.setObject(1, TenantContext.tenantId()); query.setObject(2, closure.operationId); check(query.executeUpdate() == 1)
        }
    }
}
