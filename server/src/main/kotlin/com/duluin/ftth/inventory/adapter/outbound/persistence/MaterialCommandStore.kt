package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class MaterialCommandStore(private val jdbc: WarehouseCommandJdbc) {
    fun replay(resource: UUID, action: String, key: String, canonical: WarehouseCanonicalPayload,
        authority: com.duluin.ftth.common.security.AuthorityFence, cutover: TenantCutoverFence): WarehouseOperationReceipt? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|material|$action|$key")
        sql.query("SELECT * FROM inventory_material_command WHERE tenant_id=? AND action=? AND operation_key=?", sql.tenant, action, key) {
            if (it.uuid("actor_id") != authority.identity.userId) sql.fail(WarehouseErrorCode.FORBIDDEN)
            if (it.uuid("resource_id") != resource || it.getString("payload_hash") != canonical.hash) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (it.getLong("cutover_epoch") != cutover.snapshot.epoch) sql.fail(WarehouseErrorCode.STALE_CUTOVER)
            WarehouseOperationReceipt(it.uuid("id"), it.uuid("document_id"), it.getLong("document_revision"), 200,
                it.getString("original_body"), it.getTimestamp("created_at").toInstant())
        }.singleOrNull()
    }
    fun record(resource: UUID, action: String, key: String, canonical: WarehouseCanonicalPayload,
        authority: com.duluin.ftth.common.security.AuthorityFence, cutover: TenantCutoverFence,
        document: UUID, revision: Long, body: String): WarehouseOperationReceipt = jdbc.execute { sql ->
        val id = UUID.randomUUID()
        sql.update("""INSERT INTO inventory_material_command(id,tenant_id,resource_id,action,operation_key,actor_id,authority_epoch,
            cutover_epoch,payload_hash,canonical_payload,document_id,document_revision,original_body) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            id, sql.tenant, resource, action, key, authority.identity.userId, authority.epoch, cutover.snapshot.epoch, canonical.hash, canonical.json,
            document, revision, body)
        requireNotNull(replay(resource, action, key, canonical, authority, cutover))
    }
}
