package com.karuhun.ftth.inventory.adapter.outbound.persistence

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.common.infrastructure.web.WarehouseCanonicalPayload
import com.karuhun.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseClientStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun stateCounts(kind: String,warehouses: Set<UUID>): Map<String,Long> = jdbc.execute { sql ->
        if(warehouses.isEmpty()) emptyMap() else sql.query("SELECT snapshot->>'state' state,count(*) count FROM inventory_client_document WHERE tenant_id=? AND kind=? AND warehouse_id IN ("+warehouses.joinToString(",") { "?" }+") GROUP BY snapshot->>'state'",sql.tenant,kind,*warehouses.toTypedArray()) { it.getString("state") to it.getLong("count") }.toMap()
    }
    fun actorName(id: UUID): String = jdbc.execute { sql -> requireNotNull(sql.value("SELECT name FROM app_user WHERE tenant_id=? AND id=?",sql.tenant,id)) }
    fun <T> actionSnapshot(id: UUID,action: String,type: Class<T>): T = jdbc.execute { sql ->
        sql.value("SELECT snapshot::text FROM inventory_client_command WHERE tenant_id=? AND resource_id=? AND action=?",sql.tenant,id,action)
            ?.let { mapper.readValue(it,type) } ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun <T> get(kind: String,id: UUID,type: Class<T>,lock: Boolean = false): T = jdbc.execute { sql ->
        sql.value("SELECT snapshot::text FROM inventory_client_document WHERE tenant_id=? AND kind=? AND id=?${if(lock) " FOR UPDATE" else ""}",sql.tenant,kind,id)
            ?.let { mapper.readValue(it,type) } ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun <T> list(kind: String,warehouses: Set<UUID>,page: Int,size: Int,search: String,type: Class<T>): WarehousePage<T> = jdbc.execute { sql ->
        if (warehouses.isEmpty()) return@execute WarehousePage(emptyList(),page,size,0)
        val where="tenant_id=? AND kind=? AND warehouse_id IN (${warehouses.joinToString(",") { "?" }}) AND snapshot::text ILIKE ?"
        val args=buildList<Any?> { add(sql.tenant);add(kind);addAll(warehouses);add("%$search%") }.toTypedArray()
        val total=requireNotNull(sql.value("SELECT count(*) FROM inventory_client_document WHERE $where",*args)).toLong()
        val items=sql.query("SELECT snapshot::text FROM inventory_client_document WHERE $where ORDER BY created_at DESC,id LIMIT ? OFFSET ?",*args,size,page.toLong()*size) { mapper.readValue(it.getString(1),type) }
        WarehousePage(items,page,size,total)
    }
    fun save(kind: String,view: WarehouseClientDocument) = jdbc.execute { sql ->
        val json=mapper.writeValueAsString(view)
        if(view.revision==0L) sql.update("INSERT INTO inventory_client_document(id,tenant_id,kind,warehouse_id,revision,snapshot,created_at,updated_at) VALUES (?,?,?,?,0,?::jsonb,?,?)",
            view.id,sql.tenant,kind,view.warehouseId,json,view.createdAt,view.updatedAt)
        else if(sql.update("UPDATE inventory_client_document SET revision=?,snapshot=?::jsonb,updated_at=? WHERE tenant_id=? AND id=? AND revision=?",
                view.revision,json,view.updatedAt,sql.tenant,view.id,view.revision-1)!=1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        Unit
    }
    fun replay(kind: String,action: String,key: String): ReferenceRequestCommand? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","${sql.tenant}|client|$kind|$action|$key")
        sql.query("SELECT * FROM inventory_client_command WHERE tenant_id=? AND resource_kind=? AND action=? AND operation_key=?",sql.tenant,kind,action,key) {
            ReferenceRequestCommand(it.uuid("actor_id"),it.uuid("resource_id"),it.getString("payload_hash"),it.getLong("cutover_epoch"),it.getString("permission_code"),
                WarehouseOperationReceipt(it.uuid("id"),it.uuid("resource_id"),it.getLong("revision"),it.getInt("original_status"),it.getString("snapshot"),it.getTimestamp("created_at").toInstant()))
        }.singleOrNull()
    }
    internal fun record(kind: String,action: String,key: String,view: WarehouseClientDocument,access: com.karuhun.ftth.inventory.application.service.ReferenceRequestService.Access,
        canonical: WarehouseCanonicalPayload,permission: String,notes: String,movements: List<UUID> = emptyList()): WarehouseOperationReceipt = jdbc.execute { sql ->
        val id=UUID.randomUUID();val body=mapper.writeValueAsString(view);val status=if(view.revision==0L) 201 else 200
        sql.update("""INSERT INTO inventory_client_command(id,tenant_id,resource_id,resource_kind,action,operation_key,actor_id,authority_epoch,cutover_epoch,revision,
            canonical_payload,payload_hash,snapshot,notes,permission_code,movement_ids,original_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?)""",
            id,sql.tenant,view.id,kind,action,key,access.current.fence.identity.userId,access.current.fence.epoch,access.cutover.snapshot.epoch,
            view.revision,canonical.json,canonical.hash,body,notes,permission,mapper.writeValueAsString(movements),status)
        WarehouseOperationReceipt(id,view.id,view.revision,status,body,view.updatedAt)
    }
    fun timeline(id: UUID): List<WarehouseClientEvent> = jdbc.execute { sql -> sql.query("""SELECT c.*,u.name actor_name FROM inventory_client_command c
        JOIN app_user u ON u.tenant_id=c.tenant_id AND u.id=c.actor_id WHERE c.tenant_id=? AND c.resource_id=? ORDER BY c.revision""",sql.tenant,id) {
            WarehouseClientEvent(it.uuid("id"),it.getLong("revision"),it.getString("action"),it.getString("actor_name"),it.getString("notes"),
                mapper.readValue(it.getString("movement_ids"),Array<UUID>::class.java).toList(),it.getTimestamp("created_at").toInstant()) } }
    fun reservedPurchaseBase(request: UUID,line: UUID,exclude: UUID? = null): Long = jdbc.execute { sql ->
        requireNotNull(sql.value("""SELECT coalesce(sum((l->>'quantityBase')::numeric-(l->>'receivedBase')::numeric),0)::text
            FROM inventory_client_document p CROSS JOIN LATERAL jsonb_array_elements(p.snapshot->'lines') l
            WHERE p.tenant_id=? AND p.kind='PURCHASE' AND p.snapshot->>'requestId'=? AND l->>'requestLineId'=?
                AND p.snapshot->>'state'<>'CANCELLED' AND (?::uuid IS NULL OR p.id<>?)""",sql.tenant,request.toString(),line.toString(),exclude,exclude)).toLong()
    }
    fun bindMovement(document: UUID,resource: UUID,revision: Long) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_client_movement(tenant_id,document_id,resource_id,resource_revision) VALUES (?,?,?,?)",sql.tenant,document,resource,revision);Unit }
    fun location(code: String,name: String,kind: String,parent: UUID,area: UUID?): UUID = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND code=? AND state='ACTIVE'",sql.tenant,code)?.let(UUID::fromString)
            ?: UUID.randomUUID().also { id -> sql.update("INSERT INTO inventory_location(id,tenant_id,code,name,kind,parent_location_id,area_id,issue_eligible) VALUES (?,?,?,?,?,?,?,false)",id,sql.tenant,code,name,kind,parent,area) }
    }
    fun serial(id: UUID): String? = jdbc.execute { it.value("SELECT serial_number FROM inventory_serialized_asset WHERE tenant_id=? AND id=?",it.tenant,id) }
    fun receiptAsset(id: UUID): UUID = jdbc.execute { sql -> requireNotNull(sql.value("SELECT asset.id FROM inventory_serialized_asset asset JOIN inventory_document_line line ON line.tenant_id=asset.tenant_id AND line.id=asset.origin_document_line_id WHERE asset.tenant_id=? AND line.document_id=?",sql.tenant,id)).let(UUID::fromString) }
}
