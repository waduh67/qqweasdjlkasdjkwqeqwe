package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseEventKind
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class PostingDocuments(private val sql: PostingSql) {
    fun lock(command: WarehousePost, epoch: Long) {
        val identities = (command.legs.map { it.dimension.stockIdentityId } + command.splits.map { it.parentId } +
            command.reservations.map { it.dimension.stockIdentityId }).distinct()
        val origins = identities.flatMap { identity -> sql.query("""
            SELECT line.document_id,line.id FROM inventory_segment segment
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
            JOIN inventory_document_line line ON line.tenant_id=segment.tenant_id AND line.id=coalesce(asset.origin_document_line_id,lot.origin_document_line_id)
            WHERE segment.tenant_id=? AND segment.id=?
        """,sql.tenant,identity) { it.uuid("document_id") to it.uuid("id") } }
        val source=sql.query("SELECT source_document_id,source_revision FROM inventory_document WHERE tenant_id=? AND id=? AND source_document_id IS NOT NULL",sql.tenant,command.documentId) {
            it.uuid("source_document_id") to it.getLong("source_revision")
        }.singleOrNull()
        (origins.map { it.first } + listOfNotNull(command.documentId,source?.first)).distinct().sortedBy(UUID::toString).forEach { id ->
            val mode = if(id == command.documentId) "FOR UPDATE" else "FOR SHARE"
            val row = sql.query("SELECT revision,cutover_epoch FROM inventory_document WHERE tenant_id=? AND id=? $mode",sql.tenant,id) {
                it.getLong("revision") to it.getLong("cutover_epoch")
            }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
            if(id == command.documentId && row.first != command.expectedRevision) sql.fail(WarehouseErrorCode.STALE_REVISION)
            if(id == command.documentId && row.second != epoch) sql.fail(WarehouseErrorCode.STALE_CUTOVER)
            if(source != null && id == source.first && row.first != source.second) sql.fail(WarehouseErrorCode.STALE_REVISION)
        }
        (origins.map { it.second } + command.legs.map { it.documentLineId } + command.reservations.map { it.documentLineId })
            .distinct().sortedBy(UUID::toString).forEach { id ->
                if(sql.value("SELECT id FROM inventory_document_line WHERE tenant_id=? AND id=? FOR SHARE",sql.tenant,id)==null) sql.fail(WarehouseErrorCode.NOT_FOUND)
            }
        command.legs.forEach { leg ->
            val found = sql.value("SELECT id FROM inventory_document_line WHERE tenant_id=? AND id=? AND document_id=? AND sku_id=? AND base_unit=?",
                sql.tenant,leg.documentLineId,command.documentId,leg.dimension.skuId,leg.quantity.unit)
            require(found != null) { "Leg must reference its document and snapshotted SKU unit" }
        }
    }

    fun advance(command: WarehousePost, result: WarehousePostResult, epoch: Long) {
        val operation=command.operation
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
            document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id,sql.tenant,operation.namespace,operation.key,operation.actorId,
            operation.resourceId,operation.resourceScope,operation.payloadHash,command.documentId,result.documentRevision,operation.businessAction,
            operation.originalStatus,operation.originalBody,epoch,operation.authorityEpoch)
        check(sql.update("UPDATE inventory_document SET state=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND id=? AND revision=?",
            command.nextState,result.recordedAt,sql.tenant,command.documentId,command.expectedRevision)==1)
    }

    fun header(command: WarehousePost, result: WarehousePostResult) {
        val operation=command.operation
        sql.update("""INSERT INTO inventory_movement(id,tenant_id,operation_namespace,operation_key,payload_hash,actor_id,reason,server_received_at,
            kind,state,compensates_movement_id,document_id,document_revision,operation_id) VALUES (?,?,?,?,?,?,?,?,?,'APPLIED',?,?,?,?)""",
            result.postingId,sql.tenant,operation.namespace,operation.key,operation.payloadHash,operation.actorId,command.reason,result.recordedAt,
            command.kind,command.compensatesPostingId,command.documentId,result.documentRevision,operation.id)
    }

    fun events(command: WarehousePost, result: WarehousePostResult) {
        val kind = when(command.kind) {
            MovementKind.RESERVE -> WarehouseEventKind.RESERVED
            MovementKind.RELEASE -> WarehouseEventKind.RELEASED
            MovementKind.CONSUME -> WarehouseEventKind.USE_POSTED
            MovementKind.RECEIVE -> WarehouseEventKind.RECEIVED
            MovementKind.RETURN -> WarehouseEventKind.RETURN_RECEIVED
            else -> if(command.splits.isNotEmpty()) WarehouseEventKind.SEGMENT_SPLIT else WarehouseEventKind.DISPATCHED
        }
        val supplied = command.events
        require(supplied.map { it.kind }.distinct().size == supplied.size)
        val snapshot=jacksonObjectMapper().writeValueAsString(mapOf("postingId" to result.postingId,"legs" to command.legs,
            "reservations" to command.reservations,"splits" to command.splits))
        val events = if(supplied.any { it.kind==kind }) supplied.map { event ->
            if(event.kind==kind) event.copy(payload=jacksonObjectMapper().writeValueAsString(mapOf("posting" to snapshot,"event" to event.payload))) else event
        } else supplied+PostingEvent(UUID.randomUUID(),kind,snapshot)
        events.forEach { event -> sql.update("""INSERT INTO inventory_outbox(id,tenant_id,operation_id,document_id,document_revision,event_kind,payload,recorded_at)
            VALUES (?,?,?,?,?,?,?,?)""",event.id,sql.tenant,command.operation.id,command.documentId,result.documentRevision,event.kind,event.payload,result.recordedAt) }
    }
}
