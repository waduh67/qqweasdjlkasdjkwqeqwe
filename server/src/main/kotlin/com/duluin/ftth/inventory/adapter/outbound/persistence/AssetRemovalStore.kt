package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class AssetRemovalStore(private val jdbc: WarehouseCommandJdbc, private val titles: AssetTitleStore) {
    private val mapper = jacksonObjectMapper()

    fun lockKey(key: String) = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "asset-removal:${sql.tenant}:$key")
        Unit
    }
    fun replay(key: String): AssetRemovalReplay? = jdbc.execute { sql ->
        sql.query("SELECT result,actor_id,payload_hash FROM inventory_asset_removal WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            AssetRemovalReplay(mapper.readValue(it.getString("result"), AssetRemovalResult::class.java), it.uuid("actor_id"), it.getString("payload_hash"))
        }.singleOrNull()
    }
    fun assertValid(id: UUID) = jdbc.execute { sql -> sql.value("SELECT warehouse_assert_asset_removal(?,?)", sql.tenant, id); Unit }
    fun outcome(id: UUID): AssetRemovalReplay = jdbc.execute { sql ->
        sql.query("SELECT result,actor_id,payload_hash FROM inventory_asset_removal WHERE tenant_id=? AND id=?", sql.tenant, id) {
            AssetRemovalReplay(mapper.readValue(it.getString("result"), AssetRemovalResult::class.java), it.uuid("actor_id"), it.getString("payload_hash"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun lock(assignment: UUID, replacementAsset: UUID?): CurrentAssetOwnership = jdbc.execute { sql ->
        val asset = sql.value("""SELECT asset_id FROM inventory_asset_assignment WHERE tenant_id=? AND id=?
            AND warehouse_admission='VERIFIED' AND ended_at IS NULL FOR UPDATE""", sql.tenant, assignment)
            ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        sql.query("SELECT id FROM inventory_serialized_asset WHERE tenant_id=? AND (id=? OR id=?) ORDER BY id FOR UPDATE",
            sql.tenant, UUID.fromString(asset), replacementAsset) { it.uuid("id") }
        titles.current(assignment)
    }
    fun recoveryLocation(): UUID = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_location WHERE tenant_id=? AND code='WO_TRANSIT' AND kind='TRANSIT' AND state='ACTIVE' FOR SHARE", sql.tenant) {
            it.uuid("id")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    fun append(record: AssetRemovalRecord) = jdbc.execute { sql ->
        val result = record.result
        val source = record.position.dimension
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,
            work_order_revision,cutover_epoch,authority_epoch) VALUES (?,?,?,'ASSET_REMOVAL',?,?,?,?,?,?)""",
            result.operationId, sql.tenant, "REMOVE-${result.operationId}", record.actorId, result.workOrderId, result.customerId,
            record.workOrderRevision, record.cutoverEpoch, record.authorityEpoch)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,
            tracking,quantity_base,stock_identity_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES (?,?,?,0,1,?,'EA','SERIAL',1,?,?,?,?,'CUSTOMER','SERVICEABLE',?)""",
            result.operationId, sql.tenant, result.operationId, source.skuId, result.assetId, result.assignmentId,
            source.locationId, result.customerId, result.legalOwner)
        sql.update("""INSERT INTO inventory_asset_removal(id,tenant_id,assignment_id,asset_id,customer_id,work_order_id,actor_id,
            authorization_id,replacement_assignment_id,replacement_asset_id,source_assignment_revision,source_asset_revision,title_revision,
            legal_owner,source_location_id,recovery_location_id,evidence_id,evidence_digest,work_order_revision,authority_epoch,cutover_epoch,
            operation_key,payload_hash,result,removed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            result.operationId, sql.tenant, result.assignmentId, result.assetId, result.customerId, result.workOrderId, record.actorId,
            record.authorizationId, result.replacement?.assignment?.assignmentId, result.replacement?.assignment?.assetId,
            record.ownership.assignmentRevision, record.position.assetRevision, record.ownership.titleRevision, result.legalOwner,
            source.locationId, record.recoveryLocationId, record.signature.id, record.signature.digest, record.workOrderRevision,
            record.authorityEpoch, record.cutoverEpoch, record.key, record.hash, mapper.writeValueAsString(result), result.removedAt)
        Unit
    }
}
