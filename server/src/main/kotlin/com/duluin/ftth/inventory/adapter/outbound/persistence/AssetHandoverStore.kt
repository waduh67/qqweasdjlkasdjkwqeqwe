package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.service.AssetHandoverPosition
import com.duluin.ftth.inventory.application.service.AssetHandoverRecord
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class AssetHandoverStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun authorization(assignment: UUID): UUID = jdbc.execute { sql ->
        sql.query("SELECT authorization_id FROM inventory_deployment_result WHERE tenant_id=? AND assignment_id=?", sql.tenant, assignment) {
            it.uuid("authorization_id")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lock(assignment: UUID, key: String) = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_asset_assignment WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, assignment)
            ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "asset-handover:${sql.tenant}:$key")
        Unit
    }

    fun replay(key: String): AssetHandoverRecord? = jdbc.execute { sql ->
        sql.value("SELECT snapshot FROM inventory_asset_acceptance WHERE tenant_id=? AND operation_key=?", sql.tenant, key)
            ?.let { mapper.readValue(it, AssetHandoverRecord::class.java) }
    }

    fun assertPending(assignment: UUID, revision: Long) = jdbc.execute { sql ->
        if (sql.value("""SELECT id FROM inventory_asset_assignment WHERE tenant_id=? AND id=? AND revision=?
            AND warehouse_admission='VERIFIED' AND ended_at IS NULL
            AND (legal_owner='ISP' OR (purpose='RETURN_CUSTOMER_RMA' AND legal_owner='CUSTOMER'))
            AND NOT EXISTS(SELECT FROM inventory_asset_handover WHERE tenant_id=? AND assignment_id=?)""",
                sql.tenant, assignment, revision, sql.tenant, assignment) == null) sql.fail(WarehouseErrorCode.STALE_REVISION)
    }

    fun position(assignment: AssetAssignmentRef): AssetHandoverPosition = jdbc.execute { sql ->
        val position = sql.query("""SELECT asset.sku_id,asset.id,asset.location_id,asset.custody_owner_id,asset.revision
            FROM inventory_serialized_asset asset WHERE asset.tenant_id=? AND asset.id=? AND asset.warehouse_admission='VERIFIED'
            AND asset.status='CUSTOMER_INSTALLED' AND asset.condition='SERVICEABLE' AND asset.legal_owner=?
            AND asset.custody_owner_kind='CUSTOMER' AND asset.custody_owner_id=? FOR UPDATE""", sql.tenant, assignment.assetId, assignment.legalOwner, assignment.customerId) {
            AssetHandoverPosition(PostingDimension(it.uuid("sku_id"), it.uuid("id"), null, it.uuid("location_id"),
                it.uuid("custody_owner_id"), OwnerKind.CUSTOMER, WarehouseCondition.SERVICEABLE, assignment.legalOwner), it.getLong("revision"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (sql.value("""SELECT quantity_base FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=?
            AND location_id=? AND custody_owner_id=? AND custody_owner_kind='CUSTOMER' AND status='CUSTOMER_INSTALLED'
            AND legal_owner=? AND condition='SERVICEABLE' AND quantity_base=1 FOR UPDATE""", sql.tenant,
                assignment.assetId, position.dimension.locationId, assignment.customerId, assignment.legalOwner) != "1") sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        position
    }

    fun append(record: AssetHandoverRecord) = jdbc.execute { sql ->
        val assignment = record.assignment
        sql.update("""INSERT INTO inventory_asset_handover(id,tenant_id,assignment_id,asset_id,work_order_id,customer_id,
            actor_id,evidence_id,evidence_reference,ownership_mode,accepted_at,assignment_revision)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", record.id, sql.tenant, assignment.assignmentId, assignment.assetId, assignment.workOrderId,
            assignment.customerId, record.actorId, record.signature.id, record.signature.reference, assignment.ownershipMode, record.acceptedAt,
            record.sourceAssignmentRevision)
        sql.update("""INSERT INTO inventory_asset_acceptance(tenant_id,handover_id,authorization_id,operation_id,operation_key,
            payload_hash,evidence_digest,source_asset_revision,source_title_revision,work_order_revision,authority_epoch,cutover_epoch,snapshot)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""", sql.tenant, record.id, record.authorizationId, record.operationId, record.operationKey,
            record.payloadHash, record.signature.digest, record.position.assetRevision, record.sourceTitleRevision,
            record.workOrder.revision, record.authorityEpoch, record.cutoverEpoch, mapper.writeValueAsString(record))
        if (assignment.ownershipMode == AssetOwnershipMode.LOAN) sql.update("""INSERT INTO inventory_asset_recovery_obligation
            (tenant_id,assignment_id,asset_id,customer_id,handover_id) VALUES (?,?,?,?,?)""", sql.tenant, assignment.assignmentId,
            assignment.assetId, assignment.customerId, record.id)
        Unit
    }

    fun advance(record: AssetHandoverRecord) = jdbc.execute { sql ->
        if (sql.update("UPDATE inventory_asset_assignment SET legal_owner=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
                record.assignment.legalOwner, sql.tenant, record.assignment.assignmentId, record.sourceAssignmentRevision) != 1)
            sql.fail(WarehouseErrorCode.STALE_REVISION)
        sql.value("SELECT warehouse_assert_asset_handover(?,?)", sql.tenant, record.id)
        Unit
    }

    fun assertValid(record: AssetHandoverRecord) = jdbc.execute { sql ->
        sql.value("SELECT warehouse_assert_asset_handover(?,?)", sql.tenant, record.id)
        Unit
    }
}
