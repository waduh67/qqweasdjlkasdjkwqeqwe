package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.MigrationStockInput
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class PreservedMigrationSource(val id: UUID, val table: String, val sourceId: UUID, val hash: String, val snapshot: JsonNode)
data class StoredMigrationResolution(val view: WarehouseMigrationResolution, val actorId: UUID, val payloadHash: String,
    val epoch: Long, val body: String)

@Repository
class MigrationResolutionStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun source(id: UUID): PreservedMigrationSource = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_provenance_case WHERE tenant_id=? AND id=?", sql.tenant, id) {
            PreservedMigrationSource(it.uuid("id"), it.getString("source_table"), it.uuid("source_id"),
                it.getString("source_hash"), mapper.readTree(it.getString("source_snapshot")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun latest(batch: UUID, case: UUID): StoredMigrationResolution? = jdbc.execute { sql ->
        sql.query("""SELECT * FROM inventory_migration_resolution WHERE tenant_id=? AND batch_id=? AND case_id=?
            ORDER BY revision DESC LIMIT 1""", sql.tenant, batch, case, map = ::stored).singleOrNull()
    }

    fun pending(case: UUID): Boolean = jdbc.execute { sql ->
        sql.value("SELECT warehouse_migration_pending(source_table,source_snapshot) FROM inventory_provenance_case WHERE tenant_id=? AND id=?",
            sql.tenant, case) == "t"
    }

    fun stock(case: UUID, input: MigrationStockInput): MigrationBaselineStock = jdbc.execute { sql ->
        mapper.readValue(requireNotNull(sql.value("SELECT warehouse_migration_stock(?,?,?,?)::text", case, input.skuId,
            input.sourceUnit.name, input.legalOwner.name)), MigrationBaselineStock::class.java)
    }

    fun duplicate(batch: UUID, case: UUID, target: UUID): UUID = jdbc.execute { sql ->
        UUID.fromString(sql.value("SELECT warehouse_migration_duplicate(?,?,?)", batch, case, target))
    }

    fun replay(key: String): StoredMigrationResolution? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|migration-resolution|$key")
        sql.query("SELECT * FROM inventory_migration_resolution WHERE tenant_id=? AND operation_key=?", sql.tenant, key,
            map = ::stored).singleOrNull()
    }

    fun insert(view: WarehouseMigrationResolution, key: String, payload: WarehouseCanonicalPayload, epoch: Long): String = jdbc.execute { sql ->
        val body = mapper.writeValueAsString(view)
        sql.update("""INSERT INTO inventory_migration_resolution(id,tenant_id,batch_id,case_id,source_hash,cutover_epoch,revision,
            kind,reason,evidence_manifest,stock,duplicate_case_id,duplicate_resolution_id,actor_id,operation_key,
            canonical_payload,payload_hash,original_body,created_at) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?)""",
            view.id, sql.tenant, view.batchId, view.caseId, view.sourceHash, epoch, view.revision, view.kind.name, view.reason,
            mapper.writeValueAsString(view.evidence), view.stock?.let { mapper.writeValueAsString(it) }, view.duplicateCaseId,
            view.duplicateResolutionId, view.resolvedBy, key, payload.json, payload.hash, body, view.createdAt)
        body
    }

    fun history(batch: UUID, case: UUID, page: Int, size: Int): WarehousePage<WarehouseMigrationResolution> = jdbc.execute { sql ->
        val total = requireNotNull(sql.value("SELECT count(*) FROM inventory_migration_resolution WHERE tenant_id=? AND batch_id=? AND case_id=?",
            sql.tenant, batch, case)).toLong()
        val rows = sql.query("""SELECT * FROM inventory_migration_resolution WHERE tenant_id=? AND batch_id=? AND case_id=?
            ORDER BY revision DESC LIMIT ? OFFSET ?""", sql.tenant, batch, case, size, page.toLong() * size, map = ::stored)
        WarehousePage(rows.map { it.view }, page, size, total)
    }

    private fun stored(row: java.sql.ResultSet): StoredMigrationResolution = StoredMigrationResolution(
        mapper.readValue(row.getString("original_body"), WarehouseMigrationResolution::class.java), row.uuid("actor_id"),
        row.getString("payload_hash"), row.getLong("cutover_epoch"), row.getString("original_body"))
}
