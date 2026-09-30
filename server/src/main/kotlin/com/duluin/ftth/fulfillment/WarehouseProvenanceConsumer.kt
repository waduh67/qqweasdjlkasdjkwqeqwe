package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection

@Component
class WarehouseProvenanceConsumer(private val inbox: WarehouseInboxApi, private val entityManager: EntityManager) : WarehouseOutboxDeliveryPort {
    override fun deliver(message: WarehouseDeliveryMessage) {
        check(!TransactionSynchronizationManager.isActualTransactionActive())
        check(message.event.tenantId == TenantContext.tenantId())
        inbox.consume(message.event.eventId, "fulfillment.warehouse-provenance.v1") { event ->
            require(event == message.event)
            entityManager.unwrap(Session::class.java).doWork { connection: Connection ->
                connection.prepareStatement("""INSERT INTO fulfillment_warehouse_observation
                    (id,tenant_id,operation_id,document_id,document_revision,event_kind,work_order_id) VALUES (?,?,?,?,?,?,?)""").use {
                    it.setObject(1, event.eventId); it.setObject(2, event.tenantId); it.setObject(3, event.operationId)
                    it.setObject(4, event.documentId); it.setLong(5, event.documentRevision); it.setString(6, event.kind.name)
                    it.setObject(7, event.workOrderId); check(it.executeUpdate() == 1)
                }
            }
        }
    }
}
