package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

@Repository
class ReferenceReturnStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun get(id: UUID, lock: Boolean = false): ReferenceReturnView = jdbc.execute { sql ->
        sql.value("SELECT snapshot::text FROM inventory_reference_return WHERE tenant_id=? AND id=?" + if (lock) " FOR UPDATE" else "", sql.tenant, id)
            ?.let { mapper.readValue(it, ReferenceReturnView::class.java) } ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun list(page: Int, size: Int, state: ReferenceReturnState?, search: String?, own: UUID?, warehouses: Set<UUID>?): WarehousePage<ReferenceReturnView> = jdbc.execute { sql ->
        val clauses = mutableListOf("tenant_id=?")
        val values = mutableListOf<Any?>(sql.tenant)
        if (own != null) { clauses += "technician_id=?"; values += own }
        if (warehouses != null) {
            clauses += if (warehouses.isEmpty()) "false" else "warehouse_id IN (${warehouses.joinToString(",") { "?" }})"
            values.addAll(warehouses.sortedBy(UUID::toString))
        }
        if (state != null) { clauses += "snapshot->>'state'=?"; values += state.name }
        if (search != null) {
            clauses += "(position(lower(?) in lower(snapshot->>'technicianName'))>0 OR position(lower(?) in lower(snapshot->>'skuName'))>0 OR position(lower(?) in lower(snapshot->>'reason'))>0)"
            repeat(3) { values += search }
        }
        val where = clauses.joinToString(" AND ")
        val count = requireNotNull(sql.value("SELECT count(*) FROM inventory_reference_return WHERE $where", *values.toTypedArray())).toLong()
        val rows = sql.query("SELECT snapshot::text FROM inventory_reference_return WHERE $where ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            *values.toTypedArray(), size, page.toLong() * size) { mapper.readValue(it.getString(1), ReferenceReturnView::class.java) }
        WarehousePage(rows, page, size, count)
    }

    fun save(view: ReferenceReturnView, create: Boolean) = jdbc.execute { sql ->
        val snapshot = mapper.writeValueAsString(view)
        if (create) sql.update("""INSERT INTO inventory_reference_return(id,tenant_id,technician_id,warehouse_id,revision,snapshot,created_at,updated_at)
            VALUES (?,?,?,?,0,?::jsonb,?,?)""", view.id, sql.tenant, view.technicianId, view.warehouseId, snapshot, view.createdAt, view.updatedAt)
        else if (sql.update("UPDATE inventory_reference_return SET revision=?,snapshot=?::jsonb,updated_at=? WHERE tenant_id=? AND id=? AND revision=?",
            view.revision, snapshot, view.updatedAt, sql.tenant, view.id, view.revision - 1) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        Unit
    }

    fun lockKey(action: String, key: String): ReferenceRequestCommand? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|reference-return|$action|$key")
        sql.query("SELECT * FROM inventory_reference_return_command WHERE tenant_id=? AND action=? AND operation_key=?", sql.tenant, action, key) {
            ReferenceRequestCommand(it.uuid("actor_id"), it.uuid("resource_id"), it.getString("payload_hash"), it.getLong("cutover_epoch"), it.getString("permission_code"),
                WarehouseOperationReceipt(it.uuid("id"), it.uuid("resource_id"), it.getLong("revision"), if (action == "SUBMIT") 201 else 200,
                    it.getString("snapshot"), it.getTimestamp("created_at").toInstant()))
        }.singleOrNull()
    }

    fun command(action: String, key: String, view: ReferenceReturnView, actor: UUID, authority: Long, epoch: Long,
        canonical: WarehouseCanonicalPayload, notes: String, permission: String): WarehouseOperationReceipt = jdbc.execute { sql ->
        val id = UUID.randomUUID()
        val body = mapper.writeValueAsString(view)
        sql.update("""INSERT INTO inventory_reference_return_command(id,tenant_id,resource_id,action,operation_key,actor_id,authority_epoch,cutover_epoch,
            revision,canonical_payload,payload_hash,snapshot,notes,permission_code,movement_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)""",
            id, sql.tenant, view.id, action, key, actor, authority, epoch, view.revision, canonical.json, canonical.hash, body, notes, permission, view.movementId)
        WarehouseOperationReceipt(id, view.id, view.revision, if (action == "SUBMIT") 201 else 200, body, Instant.now())
    }

    fun timeline(id: UUID): List<ReferenceReturnEvent> = jdbc.execute { sql ->
        sql.query("""SELECT command.*,actor.name actor_name FROM inventory_reference_return_command command
            JOIN app_user actor ON actor.tenant_id=command.tenant_id AND actor.id=command.actor_id
            WHERE command.tenant_id=? AND command.resource_id=? ORDER BY command.revision""", sql.tenant, id) {
            ReferenceReturnEvent(it.uuid("id"), it.getLong("revision"), it.getString("action"), it.getString("actor_name"), it.getString("notes"),
                it.optionalUuid("movement_id"), it.getTimestamp("created_at").toInstant())
        }
    }
}
