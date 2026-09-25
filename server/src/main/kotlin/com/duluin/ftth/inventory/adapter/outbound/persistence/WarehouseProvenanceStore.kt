package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.ProvenanceCustomerReference
import com.duluin.ftth.inventory.ProvenanceSourceSnapshot
import com.duluin.ftth.inventory.ProvenanceWorkOrderReference
import com.duluin.ftth.inventory.TenantCutoverSnapshot
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class MigrationCommandReplay(val actorId: UUID, val hash: String, val cutoverEpoch: Long, val body: String)

@Repository
class WarehouseProvenanceStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun replay(key: String): MigrationCommandReplay? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|migration.begin|$key")
        sql.query("SELECT * FROM inventory_migration_command WHERE tenant_id=? AND kind='BEGIN' AND operation_key=?", sql.tenant, key) {
            MigrationCommandReplay(it.uuid("actor_id"), it.getString("payload_hash"), it.getLong("resulting_cutover_epoch"), it.getString("original_body"))
        }.singleOrNull()
    }

    fun capture(cutover: TenantCutoverSnapshot, actorId: UUID, ownerSources: List<ProvenanceSourceSnapshot>) = jdbc.execute { sql ->
        if (sql.value("SELECT id FROM inventory_migration_batch WHERE tenant_id=? AND id=?", sql.tenant, cutover.migrationBatchId) != null)
            sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        sql.value("SELECT warehouse_reserve_current_identities(?)", cutover.migrationBatchId)
        sql.update("""INSERT INTO inventory_provenance_case(id,tenant_id,source_table,source_id,source_snapshot)
            SELECT warehouse_provenance_case_id(actual.tenant_id,actual.source_table,actual.source_id,actual.source_hash),
                actual.tenant_id,actual.source_table,actual.source_id,actual.source_snapshot
            FROM inventory_live_provenance_source actual WHERE actual.tenant_id=? AND NOT EXISTS (
                SELECT FROM inventory_provenance_case preserved WHERE preserved.tenant_id=actual.tenant_id
                    AND preserved.source_table=actual.source_table AND preserved.source_id=actual.source_id AND preserved.source_hash=actual.source_hash)
            ORDER BY actual.source_table,actual.source_id""", sql.tenant)
        ownerSources.forEach { source ->
            check(source.sourceTable in setOf("onu", "fulfillment_checkpoint", "fulfillment_outbox"))
            sql.update("""INSERT INTO inventory_provenance_case(id,tenant_id,source_table,source_id,source_snapshot)
                SELECT ?,?,?,?,?::jsonb WHERE NOT EXISTS (SELECT FROM inventory_provenance_case WHERE tenant_id=? AND id=?)""",
                source.id, sql.tenant, source.sourceTable, source.sourceId, source.snapshotJson, sql.tenant, source.id)
        }
        sql.update("""INSERT INTO inventory_migration_batch(id,tenant_id,cutover_epoch,snapshot_watermark,requested_by)
            VALUES (?,?,?,?,?)""", requireNotNull(cutover.migrationBatchId), sql.tenant, cutover.epoch, requireNotNull(cutover.snapshotWatermark), actorId)
    }

    fun record(key: String, payload: WarehouseCanonicalPayload, expectedEpoch: Long, cutover: TenantCutoverSnapshot,
        actorId: UUID, authorityEpoch: Long, body: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_migration_command(tenant_id,kind,operation_key,batch_id,actor_id,authority_epoch,
            expected_cutover_epoch,resulting_cutover_epoch,canonical_payload,payload_hash,original_body)
            VALUES (?,'BEGIN',?,?,?,?,?,?,?,?,?)""", sql.tenant, key, cutover.migrationBatchId, actorId, authorityEpoch,
            expectedEpoch, cutover.epoch, payload.json, payload.hash, body)
    }

    fun locationIds(): Set<UUID> = referenceIds("locationId")
    fun customerIds(): Set<UUID> = referenceIds("customerId")
    fun workOrderIds(): Set<UUID> = referenceIds("workOrderId")

    fun existingLocationIds(): Set<UUID> = jdbc.execute { sql ->
        sql.query("""SELECT DISTINCT location.id FROM inventory_location location JOIN warehouse_report_provenance_case source
            ON source.tenant_id=location.tenant_id AND source.source_snapshot->>'locationId'=location.id::text
            WHERE location.tenant_id=? ORDER BY location.id""", sql.tenant) { it.uuid("id") }.toSet()
    }

    private fun referenceIds(field: String): Set<UUID> = jdbc.execute { sql ->
        sql.query("""SELECT DISTINCT (source_snapshot->>?)::uuid id FROM warehouse_report_provenance_case
            WHERE tenant_id=? AND source_snapshot->>? IS NOT NULL ORDER BY id""", field, sql.tenant, field) { it.uuid("id") }.toSet()
    }

    fun summary(cutover: TenantCutoverSnapshot): String = jdbc.execute { sql ->
        requireNotNull(sql.value("""WITH source AS MATERIALIZED (SELECT * FROM warehouse_report_provenance_case WHERE tenant_id=?),
            manifest AS (SELECT coalesce(jsonb_agg(jsonb_build_object('caseId',id,'sourceTable',source_table,
                'sourceId',source_id,'sourceHash',source_hash) ORDER BY source_table,source_id),'[]'::jsonb) body FROM source),
            kinds AS (SELECT unnest(ARRAY['inventory_serialized_asset','inventory_balance_projection','onu','inventory_serial_tombstone',
                'inventory_movement','inventory_movement_leg','inventory_fulfillment_effect','inventory_customer_material_fact',
                'fulfillment_checkpoint','fulfillment_outbox']) kind),
            counts AS (SELECT kind,(SELECT count(*) FROM source WHERE source_table=kind) amount FROM kinds)
            SELECT jsonb_build_object('cutover',?::jsonb,'sourceCount',(SELECT count(*) FROM source),
                'preservationHash',(SELECT encode(sha256(convert_to(body::text,'UTF8')),'hex') FROM manifest),
                'sourceCounts',(SELECT jsonb_object_agg(kind,amount) FROM counts),
                'conflictGroupCount',(SELECT count(*) FROM inventory_identity_claim WHERE tenant_id=? AND state='CONFLICT'),
                'reservedIdentityCount',(SELECT count(*) FROM inventory_identity_claim WHERE tenant_id=? AND state='LEGACY_RESERVED'),
                'unitUnverifiedBalanceCount',(SELECT count(*) FROM source WHERE source_table='inventory_balance_projection'
                    AND source_snapshot->>'baseUnit' IS NULL AND (source_snapshot->>'legacyQuantity')::numeric>0),
                'pendingLegacyMovementCount',(SELECT count(*) FROM inventory_movement WHERE tenant_id=?
                    AND warehouse_admission='LEGACY_UNRESOLVED' AND state<>'APPLIED'),
                'pendingLegacyFulfillmentCount',(SELECT count(*) FROM source WHERE source_table='fulfillment_checkpoint'
                    AND warehouse_migration_pending(source_table,source_snapshot)),
                'pendingLegacyOutboxCount',(SELECT count(*) FROM source WHERE source_table='fulfillment_outbox'
                    AND warehouse_migration_pending(source_table,source_snapshot)),
                'batch',(SELECT jsonb_build_object('id',id,'cutoverEpoch',cutover_epoch,'snapshotWatermark',snapshot_watermark,
                    'sourceCount',source_count,'sourceHash',source_hash,'requestedBy',requested_by)
                    FROM inventory_migration_batch WHERE tenant_id=? AND id=?))::text""",
            sql.tenant, mapper.writeValueAsString(cutover), sql.tenant, sql.tenant, sql.tenant, sql.tenant, cutover.migrationBatchId))
    }

    fun cases(page: Int, size: Int, sourceTable: String?, id: UUID?, customers: Map<UUID, ProvenanceCustomerReference>,
        workOrders: Map<UUID, ProvenanceWorkOrderReference>): String = jdbc.execute { sql ->
        val row = """jsonb_build_object('id',source.id,'sourceTable',source.source_table,'sourceId',source.source_id,
            'sourceHash',source.source_hash,'sourceSnapshot',source.source_snapshot,
            'location',CASE WHEN location.id IS NULL THEN NULL ELSE jsonb_build_object('id',location.id,'code',location.code,'name',location.name) END,
            'customer',request.customers->(source.source_snapshot->>'customerId'),
            'workOrder',request.work_orders->(source.source_snapshot->>'workOrderId'),
            'claims',coalesce((SELECT jsonb_agg(jsonb_build_object('id',claim.id,'identityType',candidate.identity_type,
                'rawValue',candidate.raw_value,'canonicalValue',candidate.canonical_value,'state',claim.state,
                'admittedAssetId',claim.admitted_asset_id,'candidateCount',(SELECT count(*) FROM inventory_identity_candidate member
                    WHERE member.tenant_id=source.tenant_id AND member.claim_id=claim.id)) ORDER BY candidate.identity_type)
                FROM inventory_identity_candidate candidate LEFT JOIN inventory_identity_claim claim
                    ON claim.tenant_id=candidate.tenant_id AND claim.id=candidate.claim_id
                WHERE candidate.tenant_id=source.tenant_id AND candidate.source_table=source.source_table
                    AND candidate.source_id=source.source_id),'[]'::jsonb))"""
        // Explicit joins keep one row per preserved source and avoid leaking unscoped current records.
        val query = """WITH request AS (SELECT ?::uuid tenant,?::text source_table,?::uuid id,?::jsonb customers,?::jsonb work_orders),
            matches AS MATERIALIZED (SELECT source.*,$row body FROM warehouse_report_provenance_case source
                CROSS JOIN request LEFT JOIN inventory_location location ON location.tenant_id=source.tenant_id
                    AND location.id=(source.source_snapshot->>'locationId')::uuid
                WHERE source.tenant_id=request.tenant AND (request.source_table IS NULL OR source.source_table=request.source_table)
                    AND (request.id IS NULL OR source.id=request.id)),
            selected AS (SELECT * FROM matches ORDER BY source_table,source_id LIMIT ? OFFSET ?)
            SELECT ${if (id == null) "jsonb_build_object('items',coalesce((SELECT jsonb_agg(body ORDER BY source_table,source_id) FROM selected),'[]'::jsonb),'page',?,'size',?,'totalElements',(SELECT count(*) FROM matches))::text" else "(SELECT body::text FROM selected)"}"""
        val parameters = listOf<Any?>(sql.tenant, sourceTable, id, mapper.writeValueAsString(customers), mapper.writeValueAsString(workOrders), size, page.toLong() * size) +
            if (id == null) listOf(page, size) else emptyList()
        sql.value(query, *parameters.toTypedArray()) ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
}
