package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

@Service
class WarehouseInboxStore(private val jdbc: WarehouseCommandJdbc, private val cutover: InventoryTenantCutoverApi) : WarehouseInboxApi {
    @Transactional(rollbackFor = [Exception::class])
    override fun consume(eventId: UUID, consumer: String, localEffect: (WarehouseDocumentEvent) -> Unit): Boolean {
        require(consumer.matches(Regex("[a-z][a-z0-9.-]{0,119}")))
        val event = jdbc.execute { sql ->
            sql.query("""SELECT event.*,operation.cutover_epoch FROM inventory_outbox event
                JOIN inventory_operation operation ON operation.tenant_id=event.tenant_id AND operation.id=event.operation_id
                WHERE event.tenant_id=? AND event.id=? AND EXISTS (SELECT 1 FROM inventory_movement movement
                    WHERE movement.tenant_id=event.tenant_id AND movement.operation_id=event.operation_id AND movement.state='APPLIED')""",
                sql.tenant, eventId) {
                Triple(WarehouseDocumentEvent(eventId, sql.tenant, it.uuid("operation_id"), it.uuid("document_id"),
                    it.getLong("document_revision"), WarehouseEventKind.valueOf(it.getString("event_kind")), null, null,
                    it.getTimestamp("recorded_at").toInstant()), it.getLong("cutover_epoch"), it.getString("payload"))
            }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        }
        cutover.lockForCommand(event.second, WarehouseOperationClass.ORDINARY_STOCK).assertHeld()
        val hash = MessageDigest.getInstance("SHA-256").digest(event.third.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val inserted = jdbc.execute { sql ->
            sql.update("""INSERT INTO inventory_inbox(id,tenant_id,event_id,consumer,operation_id,payload_hash)
                VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,event_id,consumer) DO NOTHING""",
                UUID.randomUUID(), sql.tenant, eventId, consumer, event.first.operationId, hash) == 1
        }
        if (inserted) localEffect(event.first)
        return inserted
    }
}
