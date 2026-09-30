package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.*
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.util.UUID

@Repository
class BngFulfillmentReceiptStore(private val entityManager: EntityManager) {
    private val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
    fun lock(subscriptionId: UUID, customerId: UUID): BngFulfillmentBinding? {
        entityManager.flush()
        return entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            connection.prepareStatement("SELECT row.*,encode(sha256(convert_to(to_jsonb(row)::text,'UTF8')),'hex') hash FROM subscriber_access row WHERE tenant_id=? AND subscription_id=? ORDER BY id FOR UPDATE").use { query ->
                query.setObject(1,TenantContext.tenantId()); query.setObject(2,subscriptionId)
                query.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        if (rows.getObject("customer_id",UUID::class.java) != customerId) throw ConflictException("FULFILLMENT_BNG_BINDING")
                        val result = BngFulfillmentBinding(TenantContext.tenantId(),rows.getObject("id",UUID::class.java),subscriptionId,customerId,
                            rows.getString("hash"),rows.getString("status"),rows.getObject("nas_id",UUID::class.java))
                        if (rows.next()) throw ConflictException("FULFILLMENT_BNG_BINDING")
                        result
                    }
                }
            }
        }
    }

    fun replay(command: BngFulfillmentCommand): Boolean = entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
        connection.prepareStatement("SELECT * FROM bng_fulfillment_receipt WHERE tenant_id=? AND id=?").use { query ->
            query.setObject(1,TenantContext.tenantId()); query.setObject(2,command.reference.approvalId)
            query.executeQuery().use { rows ->
                if (!rows.next()) false else {
                    if (rows.getString("payload_hash") != command.reference.payloadHash || rows.getObject("access_id",UUID::class.java) != command.binding.accessId ||
                        rows.getString("namespace") != command.reference.namespace || rows.getString("operation_key") != command.reference.operationKey ||
                        rows.getString("action") != command.action.name) throw ConflictException("FULFILLMENT_BNG_RECEIPT")
                    true
                }
            }
        }
    }

    fun record(command: BngFulfillmentCommand, actor: UUID) {
        val before = command.binding
        val after = lock(before.subscriptionId,before.customerId) ?: throw ConflictException("FULFILLMENT_BNG_BINDING")
        val actions = entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            connection.prepareStatement("SELECT id,warehouse_bng_handoff_hash(to_jsonb(handoff)) FROM bng_action handoff WHERE tenant_id=? AND fulfillment_approval_id=? AND fulfillment_xid=pg_current_xact_id() ORDER BY id").use { query ->
                query.setObject(1,TenantContext.tenantId()); query.setObject(2,command.reference.approvalId)
                query.executeQuery().use { rows -> buildMap { while(rows.next()) put(rows.getObject(1,UUID::class.java),rows.getString(2)) } }
            }
        }
        entityManager.createNativeQuery("""INSERT INTO bng_fulfillment_receipt(id,tenant_id,access_id,customer_id,subscription_id,actor_id,namespace,operation_key,
            payload_hash,action,source_state,result_state,source_hash,result_hash,action_ids,action_bindings)
            VALUES (:id,:tenant,:access,:customer,:subscription,:actor,:namespace,:key,:hash,:action,:before,:after,:beforeHash,:afterHash,CAST(:actions AS uuid[]),CAST(:bindings AS jsonb))""")
            .setParameter("id",command.reference.approvalId).setParameter("tenant",TenantContext.tenantId()).setParameter("access",before.accessId)
            .setParameter("customer",before.customerId).setParameter("subscription",before.subscriptionId).setParameter("actor",actor)
            .setParameter("namespace",command.reference.namespace).setParameter("key",command.reference.operationKey).setParameter("hash",command.reference.payloadHash)
            .setParameter("action",command.action.name).setParameter("before",before.state).setParameter("after",after.state)
            .setParameter("beforeHash",before.revision).setParameter("afterHash",after.revision)
            .setParameter("actions",actions.keys.sortedBy(UUID::toString).joinToString(",","{","}"))
            .setParameter("bindings",mapper.writeValueAsString(actions)).executeUpdate()
    }
}
