package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.WarehousePage
import com.duluin.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class ReferenceRequestCommand(val actorId: UUID, val resourceId: UUID, val hash: String, val epoch: Long, val permission: String, val receipt: WarehouseOperationReceipt)

@Repository
class ReferenceRequestStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun get(id: UUID, lock: Boolean = false): ReferenceRequestView = jdbc.execute { sql ->
        sql.value("SELECT snapshot::text FROM inventory_reference_request WHERE tenant_id=? AND id=?${if (lock) " FOR UPDATE" else ""}", sql.tenant, id)
            ?.let { mapper.readValue(it, ReferenceRequestView::class.java) } ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun list(page: Int, size: Int, state: ReferenceRequestState?, search: String?, own: UUID?, warehouses: Set<UUID>?, technicians: Boolean): WarehousePage<ReferenceRequestView> = jdbc.execute { sql ->
        val predicates = mutableListOf("tenant_id=?")
        val values = mutableListOf<Any?>(sql.tenant)
        if (own != null) { predicates += "technician_id=?"; values += own }
        if (warehouses != null) {
            predicates += buildList {
                if (technicians) add("technician_id IS NOT NULL")
                if (warehouses.isNotEmpty()) { add("warehouse_id IN (${warehouses.joinToString(",") { "?" }})"); values.addAll(warehouses.sortedBy(UUID::toString)) }
            }.joinToString(" OR ").let { if (it.isEmpty()) "false" else "($it)" }
        }
        if (state != null) { predicates += "snapshot->>'state'=?"; values += state.name }
        if (search != null) {
            predicates += "(position(lower(?) in lower(snapshot->>'reason'))>0 OR position(lower(?) in lower(snapshot->>'requesterName'))>0)"
            values += search; values += search
        }
        val where = predicates.joinToString(" AND ")
        val total = requireNotNull(sql.value("SELECT count(*) FROM inventory_reference_request WHERE $where", *values.toTypedArray())).toLong()
        val rows = sql.query("SELECT snapshot::text FROM inventory_reference_request WHERE $where ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            *values.toTypedArray(), size, page.toLong() * size) { mapper.readValue(it.getString(1), ReferenceRequestView::class.java) }
        WarehousePage(rows, page, size, total)
    }

    fun timeline(id: UUID): List<ReferenceRequestEvent> = jdbc.execute { sql ->
        sql.query("""SELECT command.*,actor.name actor_name FROM inventory_reference_command command
            JOIN app_user actor ON actor.tenant_id=command.tenant_id AND actor.id=command.actor_id
            WHERE command.tenant_id=? AND command.resource_kind='REQUEST' AND command.resource_id=? ORDER BY revision""", sql.tenant, id) {
            ReferenceRequestEvent(it.uuid("id"), it.getLong("revision"), it.getString("action"), it.getString("actor_name"),
                it.getString("notes"), it.optionalUuid("movement_id"), it.getTimestamp("created_at").toInstant())
        }
    }

    fun save(view: ReferenceRequestView, create: Boolean) = jdbc.execute { sql ->
        val snapshot = mapper.writeValueAsString(view)
        if (create) sql.update("""INSERT INTO inventory_reference_request(id,tenant_id,requester_id,warehouse_id,technician_id,revision,snapshot,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?::jsonb,?,?)""", view.id, sql.tenant, view.requesterId, view.warehouseId, view.technicianId, view.revision, snapshot, view.createdAt, view.updatedAt)
        else if (sql.update("UPDATE inventory_reference_request SET revision=?,snapshot=?::jsonb,updated_at=? WHERE tenant_id=? AND id=? AND revision=?",
            view.revision, snapshot, view.updatedAt, sql.tenant, view.id, view.revision - 1) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        Unit
    }

    fun settings(): ReferenceOperationalSettings = jdbc.execute { sql ->
        sql.value("SELECT snapshot::text FROM inventory_reference_settings WHERE tenant_id=?", sql.tenant)
            ?.let { mapper.readValue(it, ReferenceOperationalSettings::class.java) } ?: ReferenceOperationalSettings()
    }
    fun lockSettings() = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|reference-settings")
        Unit
    }
    fun saveSettings(view: ReferenceOperationalSettings) = jdbc.execute { sql ->
        val snapshot = mapper.writeValueAsString(view)
        if (view.revision == 1L) sql.update("INSERT INTO inventory_reference_settings(tenant_id,revision,snapshot) VALUES (?,1,?::jsonb)", sql.tenant, snapshot)
        else if (sql.update("UPDATE inventory_reference_settings SET revision=?,snapshot=?::jsonb WHERE tenant_id=? AND revision=?",
            view.revision, snapshot, sql.tenant, view.revision - 1) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        Unit
    }

    fun lockKey(action: String, key: String): ReferenceRequestCommand? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|reference-request|$action|$key")
        sql.query("SELECT * FROM inventory_reference_command WHERE tenant_id=? AND action=? AND operation_key=?", sql.tenant, action, key) {
            val receipt = WarehouseOperationReceipt(it.uuid("id"), it.uuid("resource_id"), it.getLong("revision"),
                if (action == "SUBMIT") 201 else 200, it.getString("snapshot"), it.getTimestamp("created_at").toInstant())
            ReferenceRequestCommand(it.uuid("actor_id"), it.uuid("resource_id"), it.getString("payload_hash"), it.getLong("cutover_epoch"), it.getString("permission_code"), receipt)
        }.singleOrNull()
    }

    fun command(action: String, key: String, id: UUID, revision: Long, actor: UUID, authority: Long, epoch: Long,
        canonical: String, hash: String, snapshot: Any, notes: String, permission: String, movement: UUID? = null): WarehouseOperationReceipt = jdbc.execute { sql ->
        val operation = UUID.randomUUID()
        val body = mapper.writeValueAsString(snapshot)
        sql.update("""INSERT INTO inventory_reference_command(id,tenant_id,resource_id,resource_kind,action,operation_key,actor_id,authority_epoch,cutover_epoch,
            revision,canonical_payload,payload_hash,snapshot,notes,permission_code,movement_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)""",
            operation, sql.tenant, id, if (action == "SETTINGS") "SETTINGS" else "REQUEST", action, key, actor, authority, epoch, revision, canonical, hash, body, notes, permission, movement)
        WarehouseOperationReceipt(operation, id, revision, if (action == "SUBMIT") 201 else 200, body, java.time.Instant.now())
    }
}
