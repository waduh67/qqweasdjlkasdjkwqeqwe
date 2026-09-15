package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.AssetHandoverCustomer
import com.duluin.ftth.inventory.AssetHandoverCustomerPort
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY)
class AssetHandoverCustomerAdapter(private val entityManager: EntityManager) : AssetHandoverCustomerPort {
    override fun lock(customerId: UUID, assignmentId: UUID): AssetHandoverCustomer =
        entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT customer.name,customer.status FROM customer
                JOIN customer_asset_installation installation ON installation.tenant_id=customer.tenant_id
                AND installation.customer_id=customer.id
                WHERE customer.tenant_id=? AND customer.id=? AND installation.assignment_id=? FOR UPDATE OF customer""").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, customerId); query.setObject(3, assignmentId)
                query.executeQuery().use { row ->
                    if (!row.next()) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Customer installation required"))
                    AssetHandoverCustomer(row.getString("name"), row.getString("status"))
                }
            }
        }
}
