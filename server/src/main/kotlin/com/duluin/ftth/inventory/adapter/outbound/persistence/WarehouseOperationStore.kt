package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.inventory.application.service.WarehousePreparedCommand
import org.springframework.stereotype.Repository
import java.util.UUID

data class StoredWarehouseOperation(val actorId: UUID, val resourceId: UUID, val scope: String,
    val hash: String, val cutoverEpoch: Long, val receipt: WarehouseOperationReceipt)

@Repository
class WarehouseOperationStore(private val jdbc: WarehouseCommandJdbc) {
    fun lock(command: WarehousePreparedCommand): StoredWarehouseOperation? = lockKey(command.namespace, command.key)
    fun lockKey(namespace: String, key: String): StoredWarehouseOperation? {
        jdbc.execute { sql -> sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|$namespace|$key") }
        return findKey(namespace, key)
    }
    fun findKey(namespace: String, key: String): StoredWarehouseOperation? = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_operation WHERE tenant_id=? AND namespace=? AND operation_key=?",
            sql.tenant, namespace, key) { row ->
            StoredWarehouseOperation(row.uuid("actor_id"), row.uuid("resource_id"), row.getString("resource_scope"),
                row.getString("payload_hash"), row.getLong("cutover_epoch"), WarehouseOperationReceipt(row.uuid("id"),
                     row.optionalUuid("document_id") ?: row.uuid("resource_id"), row.getLong("document_revision"), row.getInt("original_status"),
                    row.getString("original_body"), row.getTimestamp("created_at").toInstant()))
        }.singleOrNull()
    }
    fun lockDocuments(document: UUID?, references: Map<String, Long>) = jdbc.execute { sql ->
        val expected = references.filterKeys { it.startsWith("document:") }.mapKeys { UUID.fromString(it.key.substringAfter(':')) }
        (expected.keys + listOfNotNull(document)).sortedBy(UUID::toString).forEach { id ->
            val revision = sql.value("SELECT revision FROM inventory_document WHERE tenant_id=? AND id=? FOR NO KEY UPDATE", sql.tenant, id)?.toLong()
                ?: sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.NOT_FOUND)
            if (id in expected && revision != expected[id]) sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.STALE_REVISION)
        }
        if (document != null) {
            sql.query("SELECT source_document_id,source_revision FROM inventory_document WHERE tenant_id=? AND id=? AND source_document_id IS NOT NULL", sql.tenant, document) {
                it.uuid("source_document_id") to it.getLong("source_revision")
            }.singleOrNull()?.let { (id, revision) ->
                if (expected[id] != revision) sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.STALE_REVISION)
            }
        }
    }
    fun hasBusinessAction(command: WarehousePreparedCommand): Boolean = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_operation WHERE tenant_id=? AND document_id=? AND business_action=? AND document_revision=?",
            sql.tenant, command.documentId, command.posting.kind.name, command.revision) != null
    }
    fun storeIdentity(operation: UUID, command: WarehousePreparedCommand, session: String?) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_command_identity(id,tenant_id,canonical_payload,original_session_id) VALUES (?,?,?,?)",
            operation, sql.tenant, command.canonical.json, session)
    }
    fun identity(operation: UUID): String = jdbc.execute { sql ->
        sql.value("SELECT canonical_payload FROM inventory_command_identity WHERE tenant_id=? AND id=?", sql.tenant, operation)
            ?: sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
    }
    fun storeIdentity(operation: UUID, canonical: String, session: String?) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_command_identity(id,tenant_id,canonical_payload,original_session_id) VALUES (?,?,?,?)", operation, sql.tenant, canonical, session)
    }
    fun locationAreas(locations: Set<UUID>): Map<UUID, UUID?> = jdbc.execute { sql ->
        locations.associateWith { location ->
            (sql.query("SELECT area_id FROM inventory_location WHERE tenant_id=? AND id=? AND state='ACTIVE'", sql.tenant, location) {
                location to it.optionalUuid("area_id")
            }.singleOrNull() ?: sql.fail(com.duluin.ftth.inventory.WarehouseErrorCode.NOT_FOUND)).second
        }
    }

    fun storeMaster(kind: com.duluin.ftth.inventory.application.port.inbound.MasterKind,
        action: com.duluin.ftth.inventory.application.port.inbound.MasterAction, key: String, resource: UUID,
        revision: Long, actor: UUID, epoch: Long, cutoverEpoch: Long, canonical: String, hash: String,
        body: String, session: String?): WarehouseOperationReceipt {
        val id = UUID.randomUUID()
        val namespace = "warehouse.master.${kind.name.lowercase()}.${action.name.lowercase()}"
        jdbc.execute { sql ->
            sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,
                payload_hash,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,master_kind)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", id, sql.tenant, namespace, key, actor, resource, "master:${kind.name}:$resource",
                hash, revision, action.name, if (action.name == "CREATE") 201 else 200, body, cutoverEpoch, epoch, kind.name)
        }
        storeIdentity(id, canonical, session)
        return requireNotNull(findKey(namespace, key)).receipt
    }
}
