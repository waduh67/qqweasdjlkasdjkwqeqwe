package com.duluin.ftth.fieldservice.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.fieldservice.VisitFulfillmentCommand
import com.duluin.ftth.fieldservice.VisitFulfillmentResult
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.util.UUID

@Repository
class VisitFulfillmentReceiptStore(private val entityManager: EntityManager) {
    fun requireReplay(command: VisitFulfillmentCommand) {
        val reference = command.reference ?: return
        val valid = entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            connection.prepareStatement("""SELECT id FROM fieldservice_fulfillment_receipt WHERE tenant_id=? AND id=? AND visit_id=?
                AND namespace=? AND operation_key=? AND payload_hash=? AND source_revision=? AND actor_id=?""").use { query ->
                query.setObject(1,command.tenantId); query.setObject(2,reference.approvalId); query.setObject(3,command.visitId)
                query.setString(4,command.namespace); query.setString(5,command.operationKey); query.setString(6,command.payloadHash)
                query.setLong(7,command.expectedRevision); query.setObject(8,command.actorId)
                query.executeQuery().use { it.next() }
            }
        }
        if (!valid) throw ConflictException("FULFILLMENT_VISIT_RECEIPT")
    }

    fun record(command: VisitFulfillmentCommand, result: VisitFulfillmentResult) {
        val reference = command.reference ?: return
        if (reference.namespace != command.namespace || reference.operationKey != command.operationKey || reference.payloadHash != command.payloadHash)
            throw ConflictException("FULFILLMENT_VISIT_RECEIPT")
        entityManager.flush()
        val operation = entityManager.unwrap(Session::class.java).doReturningWork { connection: Connection ->
            connection.prepareStatement("SELECT id FROM fieldservice_visit_operation WHERE tenant_id=? AND namespace=? AND operation_key=? AND payload_hash=? AND visit_id=?").use { query ->
                query.setObject(1,command.tenantId); query.setString(2,command.namespace); query.setString(3,command.operationKey)
                query.setString(4,command.payloadHash); query.setObject(5,command.visitId)
                query.executeQuery().use { rows -> if (!rows.next()) throw ConflictException("FULFILLMENT_VISIT_RECEIPT"); rows.getObject(1,UUID::class.java) }
            }
        }
        entityManager.createNativeQuery("""INSERT INTO fieldservice_fulfillment_receipt(id,tenant_id,visit_id,operation_id,actor_id,namespace,operation_key,payload_hash,
            source_revision,result_revision,source_state,result_state) VALUES (:id,:tenant,:visit,:operation,:actor,:namespace,:key,:hash,:before,:after,'CHECKED_OUT','SUBMITTED')""")
            .setParameter("id",reference.approvalId).setParameter("tenant",command.tenantId).setParameter("visit",command.visitId)
            .setParameter("operation",operation).setParameter("actor",command.actorId).setParameter("namespace",command.namespace)
            .setParameter("key",command.operationKey).setParameter("hash",command.payloadHash).setParameter("before",command.expectedRevision)
            .setParameter("after",result.revision).executeUpdate()
    }
}
