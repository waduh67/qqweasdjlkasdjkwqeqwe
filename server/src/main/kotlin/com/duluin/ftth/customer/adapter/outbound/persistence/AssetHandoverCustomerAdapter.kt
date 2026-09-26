package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
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
class AssetHandoverCustomerAdapter(private val entityManager: EntityManager, private val authorities: CurrentAuthorityApi) : AssetHandoverCustomerPort {
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

    override fun lockForException(customerId: UUID, assignmentId: UUID, authority: AuthorityFence): AssetHandoverCustomer {
        authority.assertHeld()
        val current = authorities.lockCurrent()
        if (current.fence.identity != authority.identity || current.fence.epoch != authority.epoch)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_AUTHORITY, "Authority changed"))
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT customer.name,customer.status,customer.area_id FROM customer
                JOIN customer_asset_installation installation ON installation.tenant_id=customer.tenant_id AND installation.customer_id=customer.id
                WHERE customer.tenant_id=? AND customer.id=? AND installation.assignment_id=? FOR UPDATE OF customer""").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, customerId); query.setObject(3, assignmentId)
                query.executeQuery().use { row ->
                    if (!row.next()) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Customer not found"))
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && row.getObject("area_id", UUID::class.java) !in scope.ids)
                        throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Customer not found"))
                    AssetHandoverCustomer(row.getString("name"), row.getString("status"))
                }
            }
        }
    }
}
