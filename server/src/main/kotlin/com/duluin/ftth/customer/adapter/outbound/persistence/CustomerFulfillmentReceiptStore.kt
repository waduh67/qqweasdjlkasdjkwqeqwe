package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerFulfillmentCommand
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.util.UUID

data class SubscriptionFulfillmentState(val state: String, val hash: String)

@Repository
class CustomerFulfillmentReceiptStore(private val entityManager: EntityManager) {
    fun replay(command: CustomerFulfillmentCommand): Boolean = entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
        connection.prepareStatement("SELECT * FROM customer_fulfillment_receipt WHERE tenant_id=? AND id=?").use { query ->
            query.setObject(1,TenantContext.tenantId()); query.setObject(2,command.reference.approvalId)
            query.executeQuery().use { rows ->
                if (!rows.next()) false else {
                    if (rows.getString("payload_hash") != command.reference.payloadHash || rows.getObject("customer_id",UUID::class.java) != command.customerId ||
                        rows.getObject("subscription_id",UUID::class.java) != command.subscriptionId || rows.getString("action") != command.action.name ||
                        rows.getString("namespace") != command.reference.namespace || rows.getString("operation_key") != command.reference.operationKey)
                        throw ConflictException("FULFILLMENT_SUBSCRIPTION_RECEIPT")
                    true
                }
            }
        }
    }

    fun state(command: CustomerFulfillmentCommand): SubscriptionFulfillmentState {
        entityManager.flush()
        return entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            connection.prepareStatement("SELECT status,encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex') revision FROM subscription row WHERE tenant_id=? AND id=? AND customer_id=? FOR UPDATE").use { query ->
                query.setObject(1,TenantContext.tenantId()); query.setObject(2,command.subscriptionId); query.setObject(3,command.customerId)
                query.executeQuery().use { rows ->
                    if (!rows.next()) throw ConflictException("FULFILLMENT_SUBSCRIPTION_BINDING")
                    SubscriptionFulfillmentState(rows.getString("status"),rows.getString("revision"))
                }
            }
        }
    }

    fun record(command: CustomerFulfillmentCommand, actor: UUID, before: SubscriptionFulfillmentState) {
        val after = state(command)
        entityManager.createNativeQuery("""INSERT INTO customer_fulfillment_receipt(id,tenant_id,customer_id,subscription_id,actor_id,namespace,operation_key,payload_hash,
            action,source_state,result_state,source_hash,result_hash) VALUES (:id,:tenant,:customer,:subscription,:actor,:namespace,:key,:hash,:action,:before,:after,:beforeHash,:afterHash)""")
            .setParameter("id",command.reference.approvalId).setParameter("tenant",TenantContext.tenantId()).setParameter("customer",command.customerId)
            .setParameter("subscription",command.subscriptionId).setParameter("actor",actor).setParameter("namespace",command.reference.namespace)
            .setParameter("key",command.reference.operationKey).setParameter("hash",command.reference.payloadHash).setParameter("action",command.action.name)
            .setParameter("before",before.state).setParameter("after",after.state).setParameter("beforeHash",before.hash).setParameter("afterHash",after.hash).executeUpdate()
    }
}
