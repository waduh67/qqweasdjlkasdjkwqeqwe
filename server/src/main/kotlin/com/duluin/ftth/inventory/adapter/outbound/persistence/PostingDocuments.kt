package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseEventKind
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.MovementKind
import com.duluin.ftth.inventory.domain.model.LegDirection
import com.duluin.ftth.inventory.domain.model.OwnerKind
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal class PostingDocuments(private val sql: PostingSql) {
    private data class LockedDocument(val revision: Long,val epoch: Long,val kind: String,val customer: UUID?,val workOrder: UUID?)

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
        val linkedLines=linkedSources(command)
        val locked=mutableMapOf<UUID,LockedDocument>()
        (origins.map { it.first } + linkedLines.map { it.first } + listOfNotNull(command.documentId,source?.first)).distinct().sortedBy(UUID::toString).forEach { id ->
            val mode = if(id == command.documentId) "FOR UPDATE" else "FOR SHARE"
            val row = sql.query("SELECT revision,cutover_epoch,kind,customer_id,work_order_id FROM inventory_document WHERE tenant_id=? AND id=? $mode",sql.tenant,id) {
                LockedDocument(it.getLong("revision"),it.getLong("cutover_epoch"),it.getString("kind"),it.optionalUuid("customer_id"),it.optionalUuid("work_order_id"))
            }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
            if(id == command.documentId && row.revision != command.expectedRevision) sql.fail(WarehouseErrorCode.STALE_REVISION)
            if(id == command.documentId && row.epoch != epoch) sql.fail(WarehouseErrorCode.STALE_CUTOVER)
            if(source != null && id == source.first && row.revision != source.second) sql.fail(WarehouseErrorCode.STALE_REVISION)
            locked[id]=row
        }
        val accountableSources=if(command.facts.isNotEmpty() || command.usage!=null) linkedLines.filter { locked.getValue(it.first).kind=="ISSUE" }.map { it.second }.toSet() else emptySet()
        (origins.map { it.second } + linkedLines.map { it.second } + command.legs.map { it.documentLineId } + command.reservations.map { it.documentLineId })
            .distinct().sortedBy(UUID::toString).forEach { id ->
                val mode=if(id in accountableSources) "FOR NO KEY UPDATE" else "FOR SHARE"
                if(sql.value("SELECT id FROM inventory_document_line WHERE tenant_id=? AND id=? $mode",sql.tenant,id)==null) sql.fail(WarehouseErrorCode.NOT_FOUND)
            }
        val bindings=PostingLineBindings(sql,command)
        val quantities=bindings.validate()
        if(linkedSources(command)!=linkedLines) sql.fail(WarehouseErrorCode.STALE_REVISION)
        val document=locked.getValue(command.documentId)
        (linkedLines.map { it.first } + listOfNotNull(source?.first)).distinct().forEach { id ->
            val issue=locked.getValue(id)
            if(issue.kind=="ISSUE") require(issue.customer==document.customer && issue.workOrder==document.workOrder) { "Source issue and posting document context mismatch" }
        }
        command.facts.forEach { fact ->
            require(fact.customerId==document.customer && fact.workOrderId==document.workOrder) { "Material fact and posting document context mismatch" }
            if(fact.installed) {
                val sink=command.legs.single { it.direction==LegDirection.IN && it.dimension.stockIdentityId==fact.stockIdentityId }.dimension
                require(sink.custodianKind==OwnerKind.CUSTOMER && sink.custodianId==fact.customerId) { "Consumed sink must retain the document customer custody" }
            }
        }
        command.usage?.let { require(it.workOrderId==document.workOrder) { "Usage snapshot and posting document context mismatch" } }
        validateIssueSources(command,source?.first?.takeIf { locked.getValue(it).kind=="ISSUE" },bindings,quantities)
    }

    private fun validateIssueSources(command: WarehousePost, declaredIssue: UUID?, bindings: PostingLineBindings, quantities: Map<UUID,StockQuantity>) {
        val posted=mutableMapOf<UUID,StockQuantity>()
        val accountable=command.facts.isNotEmpty() || command.usage!=null
        command.legs.map { it.documentLineId }.distinct().sortedBy(UUID::toString).forEach { line ->
            val source=sql.query("""SELECT line.stock_identity_id current_identity,line.sku_id current_sku,line.base_unit current_unit,line.quantity_base,
                source.stock_identity_id issued_identity,source.sku_id issued_sku,source.base_unit issued_unit,source.accepted_base,source.quantity_base issued_quantity,
                source.id issued_line,source.document_id issued_document,document.kind,document.state
                FROM inventory_document_line line LEFT JOIN inventory_document_line source ON source.tenant_id=line.tenant_id AND source.id=line.source_line_id
                LEFT JOIN inventory_document document ON document.tenant_id=source.tenant_id AND document.id=source.document_id
                WHERE line.tenant_id=? AND line.id=?""",sql.tenant,line) {
                MaterialSource(it.optionalUuid("current_identity"),it.uuid("current_sku"),it.getString("current_unit"),it.getLong("quantity_base"),
                    it.optionalUuid("issued_identity"),it.optionalUuid("issued_sku"),it.getString("issued_unit"),it.getLong("accepted_base"),
                    it.optionalUuid("issued_document"),it.getString("kind"),it.getString("state"),it.optionalUuid("issued_line"),it.getLong("issued_quantity"))
            }.single()
            if(declaredIssue!=null) require(source.document==declaredIssue) { "Declared source issue requires an explicit matching issue line" }
            if(source.kind=="ISSUE") {
                val limit=if(accountable) source.accepted else source.issuedQuantity
                val states=if(accountable) setOf("RECEIVED","PART_RECEIVED") else setOf("PICKED","DISPATCHED","RECEIVED","PART_RECEIVED")
                require(source.sku==source.issuedSku && source.unit==source.issuedUnit && source.quantity<=limit && limit>0 &&
                    source.state in states) { "Actual posting must match its source issue quantity, state and unit" }
                val issuedIdentity=requireNotNull(source.issuedIdentity)
                bindings.requireDescendant(requireNotNull(source.identity),issuedIdentity)
                command.legs.filter { it.documentLineId==line }.forEach { bindings.requireDescendant(it.dimension.stockIdentityId,issuedIdentity) }
                val issuedLine=requireNotNull(source.line)
                val quantity=quantities.getValue(line)
                val amount=(posted[issuedLine] ?: StockQuantity.of(0,quantity.unit))+quantity
                posted[issuedLine]=amount
                val prior=if(accountable) sql.value("""SELECT coalesce(sum(fact.quantity_base::numeric),0) FROM inventory_customer_material_fact fact
                    JOIN inventory_movement movement ON movement.tenant_id=fact.tenant_id AND movement.id=fact.posting_id AND movement.state='APPLIED'
                    WHERE fact.tenant_id=? AND fact.warehouse_admission='VERIFIED' AND EXISTS (
                        SELECT 1 FROM inventory_movement_leg leg JOIN inventory_document_line line ON line.tenant_id=leg.tenant_id AND line.id=leg.document_line_id
                        WHERE leg.tenant_id=fact.tenant_id AND leg.movement_id=fact.posting_id AND leg.stock_identity_id=fact.stock_identity_id AND leg.direction='IN' AND line.source_line_id=?
                    )""",sql.tenant,issuedLine)!!.toLong() else 0L
                require((StockQuantity.of(prior,StockUnit.valueOf(source.unit))+amount).quantityBase<=limit) { "Actual quantity exceeds the source issue allocation" }
            }
        }
    }

    private data class MaterialSource(val identity: UUID?,val sku: UUID,val unit: String,val quantity: Long,val issuedIdentity: UUID?,
        val issuedSku: UUID?,val issuedUnit: String?,val accepted: Long,val document: UUID?,val kind: String?,val state: String?,val line: UUID?,val issuedQuantity: Long)

    private fun linkedSources(command: WarehousePost): List<Pair<UUID,UUID>> = command.legs.map { it.documentLineId }.distinct().sortedBy(UUID::toString).flatMap { line ->
        sql.query("""SELECT source.document_id,source.id FROM inventory_document_line line
            JOIN inventory_document_line source ON source.tenant_id=line.tenant_id AND source.id=line.source_line_id
            WHERE line.tenant_id=? AND line.id=?""",sql.tenant,line) { it.uuid("document_id") to it.uuid("id") }
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
