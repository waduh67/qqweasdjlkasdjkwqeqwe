package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerFulfillmentBinding
import com.duluin.ftth.customer.CustomerFulfillmentLockApi
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.util.UUID

@Component
class CustomerFulfillmentLocks(private val entityManager: EntityManager) : CustomerFulfillmentLockApi {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(customerId: UUID, subscriptionId: UUID?): CustomerFulfillmentBinding =
        entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            val customer = connection.prepareStatement("SELECT encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex') FROM customer row WHERE tenant_id=? AND id=? FOR UPDATE").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, customerId)
                query.executeQuery().use { rows -> if (!rows.next()) throw ConflictException("FULFILLMENT_CUSTOMER_MISSING"); rows.getString(1) }
            }
            val subscription = subscriptionId?.let { id ->
                connection.prepareStatement("SELECT encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex') FROM subscription row WHERE tenant_id=? AND id=? AND customer_id=? FOR UPDATE").use { query ->
                    query.setObject(1, TenantContext.tenantId()); query.setObject(2, id); query.setObject(3, customerId)
                    query.executeQuery().use { rows -> if (!rows.next()) throw ConflictException("FULFILLMENT_SUBSCRIPTION_MISMATCH"); rows.getString(1) }
                }
            }
            CustomerFulfillmentBinding(customerId, customer, subscriptionId, subscription)
        }
}
