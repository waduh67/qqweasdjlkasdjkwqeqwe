package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseCommandJdbc
import com.duluin.ftth.inventory.adapter.outbound.persistence.uuid
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.workorder.application.port.inbound.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

data class ReferenceWorkOrderCommand(val actorId: UUID, val resourceId: UUID, val resourceKind: String,
    val hash: String, val epoch: Long, val permission: String, val receipt: WarehouseOperationReceipt)

@Repository
class ReferenceWorkOrderStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun lockTypes() = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|reference-workorder-types")
        Unit
    }
    fun ensureDefaults() = jdbc.execute { sql ->
        sql.value("SELECT warehouse_seed_work_order_types(?)", sql.tenant)
        Unit
    }
    fun types(): List<ReferenceWorkOrderTypeView> = jdbc.execute { sql ->
        sql.query("SELECT snapshot::text FROM work_order_reference_type WHERE tenant_id=? ORDER BY lower(name),id", sql.tenant) {
            mapper.readValue(it.getString(1), ReferenceWorkOrderTypeView::class.java)
        }
    }
    fun type(id: UUID): ReferenceWorkOrderTypeView = types().singleOrNull { it.id == id } ?: missing()
    fun typeUsed(id: UUID): Boolean = jdbc.execute { sql ->
        requireNotNull(sql.value("SELECT count(*) FROM work_order_reference WHERE tenant_id=? AND type_id=?", sql.tenant, id)).toLong() > 0
    }
    fun saveType(view: ReferenceWorkOrderTypeView, create: Boolean) = jdbc.execute { sql ->
        if (create) sql.update("INSERT INTO work_order_reference_type(id,tenant_id,name,revision,snapshot) VALUES (?,?,?,0,?::jsonb)",
            view.id, sql.tenant, view.name, mapper.writeValueAsString(view))
        else if (sql.update("UPDATE work_order_reference_type SET name=?,revision=?,snapshot=?::jsonb WHERE tenant_id=? AND id=? AND revision=?",
            view.name, view.revision, mapper.writeValueAsString(view), sql.tenant, view.id, view.revision - 1) != 1) stale()
        Unit
    }
    fun get(id: UUID, lock: Boolean = false): ReferenceWorkOrderView = jdbc.execute { sql ->
        sql.value("SELECT snapshot::text FROM work_order_reference WHERE tenant_id=? AND id=?" + if (lock) " FOR UPDATE" else "", sql.tenant, id)
            ?.let { mapper.readValue(it, ReferenceWorkOrderView::class.java) } ?: missing()
    }
    fun save(view: ReferenceWorkOrderView, create: Boolean) = jdbc.execute { sql ->
        val body = mapper.writeValueAsString(view)
        if (create) sql.update("""INSERT INTO work_order_reference(id,tenant_id,type_id,technician_id,area_id,revision,snapshot)
            VALUES (?,?,?,?,?,0,?::jsonb)""", view.id, sql.tenant, view.type.id, view.technicianId, view.areaId, body)
        else if (sql.update("UPDATE work_order_reference SET technician_id=?,area_id=?,revision=?,snapshot=?::jsonb WHERE tenant_id=? AND id=? AND revision=?",
            view.technicianId, view.areaId, view.revision, body, sql.tenant, view.id, view.revision - 1) != 1) stale()
        Unit
    }
    fun list(page: Int, size: Int, scope: AuthorityScope, own: UUID?, state: ReferenceWorkOrderState?, search: String?,
        overdueOnly: Boolean, days: Int): WarehousePage<ReferenceWorkOrderView> = jdbc.execute { sql ->
        val clauses = mutableListOf("tenant_id=?")
        val values = mutableListOf<Any?>(sql.tenant)
        if (scope is AuthorityScope.Restricted) {
            clauses += if (scope.ids.isEmpty()) "false" else "area_id IN (${scope.ids.joinToString(",") { "?" }})"
            values.addAll(scope.ids.sortedBy(UUID::toString))
        }
        if (own != null) { clauses += "technician_id=?"; values += own }
        if (state != null) { clauses += "snapshot->>'state'=?"; values += state.name }
        if (search != null) { clauses += "position(lower(?) in lower(concat(snapshot->>'title',' ',snapshot->>'code',' ',snapshot->>'technicianName')))>0"; values += search }
        if (overdueOnly) {
            clauses += "snapshot->>'state'='PENDING' AND (snapshot->>'lastActivityAt')::timestamptz+make_interval(days=>?)<=clock_timestamp()"
            values += days
        }
        val where = clauses.joinToString(" AND ")
        val total = requireNotNull(sql.value("SELECT count(*) FROM work_order_reference WHERE $where", *values.toTypedArray())).toLong()
        val rows = sql.query("SELECT snapshot::text FROM work_order_reference WHERE $where ORDER BY (snapshot->>'createdAt')::timestamptz DESC,id LIMIT ? OFFSET ?",
            *values.toTypedArray(), size, page.toLong() * size) { mapper.readValue(it.getString(1), ReferenceWorkOrderView::class.java) }
        WarehousePage(rows, page, size, total)
    }
    fun lockKey(action: String, key: String): ReferenceWorkOrderCommand? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|reference-workorder|$action|$key")
        sql.query("SELECT * FROM work_order_reference_command WHERE tenant_id=? AND action=? AND operation_key=?", sql.tenant, action, key) {
            ReferenceWorkOrderCommand(it.uuid("actor_id"), it.uuid("resource_id"), it.getString("resource_kind"), it.getString("payload_hash"),
                it.getLong("cutover_epoch"), it.getString("permission_code"), WarehouseOperationReceipt(it.uuid("id"), it.uuid("resource_id"),
                    it.getLong("revision"), it.getInt("original_status"), it.getString("snapshot"), it.getTimestamp("created_at").toInstant()))
        }.singleOrNull()
    }
    fun command(action: String, key: String, resource: UUID, kind: String, revision: Long, actor: UUID, authority: Long,
        epoch: Long, canonical: WarehouseCanonicalPayload, snapshot: Any, notes: String, permission: String, status: Int = 200): WarehouseOperationReceipt = jdbc.execute { sql ->
        val id = UUID.randomUUID()
        val body = mapper.writeValueAsString(snapshot)
        sql.update("""INSERT INTO work_order_reference_command(id,tenant_id,resource_id,resource_kind,action,operation_key,revision,actor_id,authority_epoch,
            cutover_epoch,canonical_payload,payload_hash,snapshot,notes,permission_code,original_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)""",
            id, sql.tenant, resource, kind, action, key, revision, actor, authority, epoch, canonical.json, canonical.hash, body, notes, permission, status)
        WarehouseOperationReceipt(id, resource, revision, status, body, Instant.now())
    }
    fun timeline(id: UUID): List<ReferenceWorkOrderEvent> = jdbc.execute { sql ->
        sql.query("""SELECT command.*,actor.name actor_name FROM work_order_reference_command command
            JOIN app_user actor ON actor.tenant_id=command.tenant_id AND actor.id=command.actor_id
            WHERE command.tenant_id=? AND command.resource_kind='WO' AND command.resource_id=? ORDER BY command.revision""", sql.tenant, id) {
            ReferenceWorkOrderEvent(it.uuid("id"), it.getLong("revision"), it.getString("action"), it.getString("actor_name"),
                it.getString("notes"), it.getTimestamp("created_at").toInstant())
        }
    }
    private fun missing(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.NOT_FOUND, "Work order tidak ditemukan"))
    private fun stale(): Nothing = throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_REVISION, "Work order sudah berubah"))
}
