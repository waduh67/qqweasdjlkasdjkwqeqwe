package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class StoredMigrationEvidence(val view: WarehouseMigrationEvidence, val objectKey: String, val originalBody: String,
    val actorId: UUID, val payloadHash: String, val cutoverEpoch: Long)

@Repository
class MigrationEvidenceStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun lockBatch(batch: UUID, epoch: Long) = jdbc.execute { sql ->
        if (sql.value("SELECT warehouse_lock_migration_batch(?,?)", sql.tenant, batch)?.toLong() != epoch)
            sql.fail(WarehouseErrorCode.STALE_CUTOVER)
    }

    fun caseHash(batch: UUID, case: UUID): String = jdbc.execute { sql ->
        sql.value("""SELECT source.source_hash FROM inventory_provenance_case source JOIN inventory_migration_batch batch
            ON batch.tenant_id=source.tenant_id AND batch.id=? WHERE source.tenant_id=? AND source.id=?
                AND EXISTS (SELECT FROM jsonb_array_elements(batch.source_manifest) member
                    WHERE member->>'caseId'=source.id::text AND member->>'sourceHash'=source.source_hash)""", batch, sql.tenant, case)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun replay(key: String): StoredMigrationEvidence? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|migration-evidence|$key")
        sql.query("SELECT * FROM inventory_migration_evidence WHERE tenant_id=? AND operation_key=?", sql.tenant, key, map = ::stored).singleOrNull()
    }

    fun insert(view: WarehouseMigrationEvidence, objectKey: String, key: String, payload: WarehouseCanonicalPayload, epoch: Long): StoredMigrationEvidence = jdbc.execute { sql ->
        val body = mapper.writeValueAsString(view)
        sql.update("""INSERT INTO inventory_migration_evidence(id,tenant_id,batch_id,case_id,source_hash,cutover_epoch,actor_id,label,
            content_type,size_bytes,sha256,object_key,operation_key,canonical_payload,payload_hash,original_body,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", view.id, sql.tenant, view.batchId, view.caseId, view.sourceHash, epoch,
            view.uploadedBy, view.label, view.contentType, view.sizeBytes, view.sha256, objectKey, key, payload.json, payload.hash, body, view.createdAt)
        StoredMigrationEvidence(view, objectKey, body, view.uploadedBy, payload.hash, epoch)
    }

    fun get(batch: UUID, case: UUID, id: UUID): StoredMigrationEvidence = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_migration_evidence WHERE tenant_id=? AND batch_id=? AND case_id=? AND id=?",
            sql.tenant, batch, case, id, map = ::stored).singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun list(batch: UUID, case: UUID, page: Int, size: Int): WarehousePage<WarehouseMigrationEvidence> = jdbc.execute { sql ->
        val total = requireNotNull(sql.value("SELECT count(*) FROM inventory_migration_evidence WHERE tenant_id=? AND batch_id=? AND case_id=?",
            sql.tenant, batch, case)).toLong()
        val rows = sql.query("""SELECT * FROM inventory_migration_evidence WHERE tenant_id=? AND batch_id=? AND case_id=?
            ORDER BY created_at DESC,id LIMIT ? OFFSET ?""", sql.tenant, batch, case, size, page.toLong() * size, map = ::stored)
        WarehousePage(rows.map { it.view }, page, size, total)
    }

    fun settledObjectKey(batch: UUID, id: UUID): String? = jdbc.execute { sql ->
        sql.update("SET LOCAL lock_timeout='2s'")
        sql.update("SET LOCAL statement_timeout='5s'")
        // Same lock as the upload. This settles ambiguous commit before deciding whether deletion is safe.
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|migration-batch|$batch")
        sql.value("SELECT object_key FROM inventory_migration_evidence WHERE tenant_id=? AND batch_id=? AND id=?", sql.tenant, batch, id)
    }

    private fun stored(row: java.sql.ResultSet): StoredMigrationEvidence {
        val body = row.getString("original_body")
        return StoredMigrationEvidence(mapper.readValue(body, WarehouseMigrationEvidence::class.java), row.getString("object_key"), body,
            row.uuid("actor_id"), row.getString("payload_hash"), row.getLong("cutover_epoch"))
    }
}
