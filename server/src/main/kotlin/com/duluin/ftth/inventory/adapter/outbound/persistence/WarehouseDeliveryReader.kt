package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import java.util.UUID

@Repository
class WarehouseDeliveryReader(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun read(lease: WarehouseDeliveryLease): WarehouseDeliveryMessage = jdbc.execute { sql ->
        sql.query("""SELECT event.*,document.work_order_id,movement.id posting_id FROM inventory_outbox event
            JOIN inventory_outbox_delivery delivery ON delivery.tenant_id=event.tenant_id AND delivery.id=event.id
            JOIN inventory_document document ON document.tenant_id=event.tenant_id AND document.id=event.document_id
            JOIN inventory_movement movement ON movement.tenant_id=event.tenant_id AND movement.operation_id=event.operation_id AND movement.state='APPLIED'
            WHERE event.tenant_id=? AND event.id=? AND delivery.state='LEASED' AND delivery.lease_owner=?
                AND delivery.lease_token=? AND delivery.attempts=? AND delivery.lease_until>clock_timestamp()""",
            sql.tenant, lease.eventId, lease.owner, lease.token, lease.attempt) { row ->
            val kind = WarehouseEventKind.valueOf(row.getString("event_kind"))
            if (kind !in supported) throw UnsupportedWarehouseDelivery()
            val payload = row.getString("payload")
            require(payload.length <= 1_048_576)
            val tree = mapper.readTree(payload)
            val posting = if (tree.has("posting")) {
                require(tree.properties().map { it.key }.toSet() == setOf("posting", "event"))
                mapper.readTree(tree.path("posting").asString())
            } else tree
            validate(posting, row.uuid("posting_id"))
            WarehouseDeliveryMessage(WarehouseDocumentEvent(lease.eventId, sql.tenant, row.uuid("operation_id"),
                row.uuid("document_id"), row.getLong("document_revision"), kind, row.optionalUuid("work_order_id"), null,
                row.getTimestamp("recorded_at").toInstant()), row.uuid("posting_id"))
        }.singleOrNull() ?: throw IllegalStateException("Delivery lease unavailable")
    }
    private fun validate(node: JsonNode, postingId: UUID) {
        require(node.isObject && node.properties().map { it.key }.toSet() == setOf("postingId", "legs", "reservations", "splits"))
        require(node.path("postingId").asString() == postingId.toString())
        require(node.path("legs").isArray && node.path("reservations").isArray && node.path("splits").isArray)
    }
    companion object {
        val supported = setOf(WarehouseEventKind.RECEIVED, WarehouseEventKind.RESERVED, WarehouseEventKind.RELEASED,
            WarehouseEventKind.DISPATCHED, WarehouseEventKind.SEGMENT_SPLIT, WarehouseEventKind.USE_POSTED, WarehouseEventKind.RETURN_RECEIVED)
    }
}
class UnsupportedWarehouseDelivery : RuntimeException("Unsupported warehouse event")
