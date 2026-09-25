package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class StoredMigrationOpening(val view: WarehouseMigrationOpening, val payloadHash: String, val body: String)

@Repository
class MigrationOpeningStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun list(batch: UUID, page: Int, size: Int, access: WarehouseQueryAccess): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page, size = size), access)
        query.result(query.page("""SELECT opening.id,opening.created_at,jsonb_build_object(
            'id',opening.id,'batchId',opening.batch_id,'code',document.code,'state',document.state,
            'reviewHash',opening.review_hash,'requestedBy',opening.actor_id,'createdAt',${queryTime("opening.created_at")},
            'migrationReference',opening.original_body::jsonb->>'migrationReference',
            'reviewLocation',jsonb_build_object('id',location.id,'code',location.code,'name',location.name)) body
            FROM inventory_migration_opening_request opening JOIN inventory_document document
                ON document.tenant_id=opening.tenant_id AND document.id=opening.id
            JOIN visible_locations location ON location.tenant_id=opening.tenant_id AND location.id=opening.review_location_id,request
            WHERE opening.tenant_id=request.tenant AND opening.batch_id=?""", "body", "created_at"), batch)
    }

    fun review(batch: UUID): WarehouseMigrationReview = jdbc.execute { sql ->
        val manifest = sql.value("SELECT warehouse_migration_review_manifest(?)::text", batch) ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        val result = sql.query("""SELECT encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex') hash,
            warehouse_migration_review_issues(?::jsonb)::text issues""", manifest, manifest) { it.getString("hash") to it.getString("issues") }.single()
        WarehouseMigrationReview(mapper.readValue(manifest, MigrationReviewManifest::class.java), result.first,
            mapper.readValue(result.second, Array<MigrationReviewIssue>::class.java).toList())
    }

    fun replay(key: String): StoredMigrationOpening? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|migration-opening|$key")
        sql.query("SELECT original_body,payload_hash FROM inventory_migration_opening_request WHERE tenant_id=? AND operation_key=?",
            sql.tenant, key, map = ::stored).singleOrNull()
    }

    fun get(batch: UUID, id: UUID): StoredMigrationOpening = jdbc.execute { sql ->
        sql.query("SELECT original_body,payload_hash FROM inventory_migration_opening_request WHERE tenant_id=? AND batch_id=? AND id=?",
            sql.tenant, batch, id, map = ::stored).singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun find(id: UUID): StoredMigrationOpening? = jdbc.execute { sql ->
        sql.query("SELECT original_body,payload_hash FROM inventory_migration_opening_request WHERE tenant_id=? AND id=?",
            sql.tenant, id, map = ::stored).singleOrNull()
    }

    fun batchRequester(batch: UUID): UUID = jdbc.execute { sql ->
        UUID.fromString(sql.value("SELECT requested_by FROM inventory_migration_batch WHERE tenant_id=? AND id=?", sql.tenant, batch)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND))
    }

    fun lockHistory(batch: UUID) = jdbc.execute { sql ->
        sql.value("SELECT warehouse_lock_migration_history(?,?)", sql.tenant, batch)
        Unit
    }

    fun currentAccess(batch: UUID): MigrationOpeningScope = jdbc.execute { sql ->
        mapper.readValue(requireNotNull(sql.value("SELECT warehouse_migration_source_access(?)::text", batch)), MigrationOpeningScope::class.java)
    }

    fun insert(view: WarehouseMigrationOpening, key: String, payload: WarehouseCanonicalPayload): String = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,source_reference,reason,migration_batch_id,
            cutover_epoch,authority_epoch) VALUES (?,?,?,'OPENING_BALANCE',?,?,?,?,?,?)""", view.id, sql.tenant, view.code,
            view.requestedBy, view.migrationReference, view.reason, view.batchId, view.cutoverEpoch, view.authorityEpoch)
        view.manifest.cases.filter { it.resolution?.kind == MigrationResolutionKind.BASELINE_STOCK }.forEachIndexed { index, source ->
            val stock = requireNotNull(source.resolution?.stock)
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
                base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES (?,?,?,0,?,?,?,?,?,?,?,'WAREHOUSE','SERVICEABLE','ISP')""", UUID.randomUUID(), sql.tenant, view.id,
                index + 1, stock.skuId, stock.baseUnit.name, stock.tracking.name, stock.quantityBase.toLong(), stock.locationId, stock.locationId)
        }
        val body = mapper.writeValueAsString(view)
        sql.update("""INSERT INTO inventory_migration_opening_request(id,tenant_id,batch_id,review_hash,review_manifest,
            review_location_id,review_location_revision,actor_id,authority_epoch,cutover_epoch,operation_key,
            canonical_payload,payload_hash,original_body,created_at) VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?)""",
            view.id, sql.tenant, view.batchId, view.reviewHash, mapper.writeValueAsString(view.manifest), view.reviewLocation.id,
            view.reviewLocation.revision, view.requestedBy, view.authorityEpoch, view.cutoverEpoch, key, payload.json, payload.hash, body, view.createdAt)
        body
    }

    private fun stored(row: java.sql.ResultSet): StoredMigrationOpening = StoredMigrationOpening(
        mapper.readValue(row.getString("original_body"), WarehouseMigrationOpening::class.java), row.getString("payload_hash"), row.getString("original_body"))
}
