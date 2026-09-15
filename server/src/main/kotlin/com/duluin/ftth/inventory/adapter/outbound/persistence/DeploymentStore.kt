package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class DeploymentStore(private val jdbc: WarehouseCommandJdbc, private val receipts: MaterialReceiptStore,
    private val totals: MaterialPhysicalTotalsStore) {
    private val mapper = jacksonObjectMapper()

    fun source(issueLine: UUID): DeploymentSource = jdbc.execute { sql ->
        val receiptId = sql.query("""SELECT receipt_id FROM inventory_material_receipt_line
            WHERE tenant_id=? AND issue_line_id=? AND accepted_base=1 AND base_unit='EA'""", sql.tenant, issueLine) { it.uuid("receipt_id") }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val receipt = receipts.get(receiptId)
        val line = receipt.lines.singleOrNull { it.selection.issueLineId == issueLine } ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val custody = line.accepted ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        sql.query("""SELECT asset.revision,asset.canonical_serial,document.kind provenance,sku.model,sku.category,issue_document.revision issue_revision
            FROM inventory_serialized_asset asset JOIN inventory_sku sku ON sku.tenant_id=asset.tenant_id AND sku.id=asset.sku_id
            JOIN inventory_document_line origin ON origin.tenant_id=asset.tenant_id AND origin.id=asset.origin_document_line_id
            JOIN inventory_document document ON document.tenant_id=origin.tenant_id AND document.id=origin.document_id
            JOIN inventory_document issue_document ON issue_document.tenant_id=asset.tenant_id AND issue_document.id=?
            WHERE asset.tenant_id=? AND asset.id=? AND asset.warehouse_admission='VERIFIED'
                AND sku.state='ACTIVE' AND sku.tracking='SERIAL' AND sku.base_unit='EA'""", receipt.issueId, sql.tenant, custody.stockIdentityId) { row ->
            DeploymentSource(receiptId, receipt.revision, row.getLong("issue_revision"), receipt.issueId, issueLine, receipt.issue.planId,
                receipt.issue.planRevision, receipt.issue.customerId ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED),
                receipt.issue.workOrderId, custody, row.getLong("revision"), row.getString("canonical_serial"),
                row.getString("model"), row.getString("category") in setOf("ONU", "ONT"), AssetProvenance.valueOf(row.getString("provenance")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockAsset(source: DeploymentSource) = jdbc.execute { sql ->
        if (sql.value("SELECT revision FROM inventory_document WHERE tenant_id=? AND id=?", sql.tenant, source.issueId)?.toLong() != source.issueRevision)
            sql.fail(WarehouseErrorCode.STALE_REVISION)
        val asset = sql.query("""SELECT revision FROM inventory_serialized_asset WHERE tenant_id=? AND id=?
            AND warehouse_admission='VERIFIED' AND status='ISSUED' AND condition='SERVICEABLE' AND legal_owner='ISP'
            AND custody_owner_kind='TECHNICIAN' AND custody_owner_id=? AND location_id=? FOR UPDATE""",
            sql.tenant, source.custody.stockIdentityId, source.custody.custodianId, source.custody.locationId) { it.getLong(1) }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (asset != source.assetRevision) sql.fail(WarehouseErrorCode.STALE_REVISION)
        sql.value("SELECT warehouse_assert_verified_segment(?,?,true)", sql.tenant, source.custody.stockIdentityId)
        val quantity = sql.query("""SELECT quantity_base FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=?
            AND status='ISSUED' AND condition='SERVICEABLE' AND legal_owner='ISP' AND custody_owner_kind='TECHNICIAN'
            AND custody_owner_id=? AND location_id=? AND quantity_base=1 FOR UPDATE""", sql.tenant, source.custody.stockIdentityId,
            source.custody.custodianId, source.custody.locationId) { it.getLong(1) }.singleOrNull()
        if (quantity != 1L) sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
        if (sql.value("SELECT id FROM inventory_asset_assignment WHERE tenant_id=? AND asset_id=? AND ended_at IS NULL",
                sql.tenant, source.custody.stockIdentityId) != null) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockKey(key: String) = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "deployment:${sql.tenant}:$key")
        Unit
    }

    fun findMint(key: String): DeploymentMint? = jdbc.execute { sql ->
        sql.query("SELECT authorization_id,mint_hash FROM inventory_deployment_execution WHERE tenant_id=? AND mint_key=?", sql.tenant, key) {
            DeploymentMint(preview(it.uuid("authorization_id")), key, it.getString("mint_hash"))
        }.singleOrNull()
    }

    fun read(id: UUID): DeploymentPermit = jdbc.execute { sql ->
        if (sql.value("SELECT id FROM inventory_deployment_authorization WHERE tenant_id=? AND id=?", sql.tenant, id) == null)
            sql.fail(WarehouseErrorCode.NOT_FOUND)
        sql.query("""SELECT permit.consumed,execution.binding,execution.source FROM warehouse_read_deployment_authorization(?,?) permit
            JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id""",
            sql.tenant, id) { DeploymentPermit(mapper.readValue(it.getString("binding"), DeploymentBinding::class.java),
                mapper.readValue(it.getString("source"), DeploymentSource::class.java), it.getBoolean("consumed"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun preview(id: UUID): DeploymentPermit = jdbc.execute { sql ->
        sql.query("""SELECT permit.consumed,execution.binding,execution.source FROM inventory_deployment_authorization permit
            JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
            WHERE permit.tenant_id=? AND permit.id=?""", sql.tenant, id) {
            DeploymentPermit(mapper.readValue(it.getString("binding"), DeploymentBinding::class.java),
                mapper.readValue(it.getString("source"), DeploymentSource::class.java), it.getBoolean("consumed"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun lock(id: UUID): DeploymentPermit = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_deployment_authorization WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id)
        preview(id)
    }

    fun mint(mint: DeploymentMint) = jdbc.execute { sql ->
        val binding = mint.permit.binding
        val source = mint.permit.source
        sql.update("""INSERT INTO inventory_deployment_authorization(id,tenant_id,asset_id,issue_line_id,work_order_id,customer_id,
            actor_id,purpose,ownership_mode,operation_id,expected_asset_revision,expected_work_order_revision,expected_plan_revision,
            expected_issue_revision,authority_epoch,cutover_epoch,previous_assignment_id,expected_assignment_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            binding.authorizationId, sql.tenant, binding.assetId, binding.issueLineId, binding.workOrderId, binding.customerId,
            binding.actorId, binding.purpose, binding.ownershipMode, binding.operationId, binding.assetRevision,
            binding.revisions.workOrderRevision, binding.revisions.planRevision, binding.issueRevision, binding.authorityEpoch, binding.cutoverEpoch,
            binding.previousAssignmentId, binding.previousAssignmentRevision)
        sql.update("""INSERT INTO inventory_deployment_execution(tenant_id,authorization_id,receipt_id,receipt_revision,use_revision,
            plan_id,mint_key,mint_hash,binding,source) VALUES (?,?,?,?,?,?,?,?,?,?)""", sql.tenant, binding.authorizationId,
            source.receiptId, source.receiptRevision, binding.revisions.useRevision, source.planId, mint.key, mint.hash,
            mapper.writeValueAsString(binding), mapper.writeValueAsString(source))
        Unit
    }

    fun result(id: UUID): DeploymentResult = jdbc.execute { sql ->
        sql.value("SELECT warehouse_assert_deployment_result(?,?)", sql.tenant, id)
        sql.query("SELECT result,consume_key,consume_hash FROM inventory_deployment_result WHERE tenant_id=? AND authorization_id=?", sql.tenant, id) {
            DeploymentResult(mapper.readValue(it.getString("result"), DeploymentConsumption::class.java), it.getString("consume_key"), it.getString("consume_hash"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun consume(permit: DeploymentPermit, result: DeploymentResult) = jdbc.execute { sql ->
        val binding = permit.binding
        val operation = binding.operationId
        sql.update("""UPDATE inventory_deployment_authorization SET consumed=true,consumed_at=clock_timestamp(),revision=revision+1
            WHERE tenant_id=? AND id=? AND NOT consumed""", sql.tenant, binding.authorizationId).also {
            if (it != 1) sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        }
        sql.update("""INSERT INTO inventory_deployment_result(tenant_id,authorization_id,assignment_id,posting_id,operation_id,
            use_revision,consume_key,consume_hash,result,creates_onu) VALUES (?,?,?,?,?,?,?,?,?,?)""", sql.tenant, binding.authorizationId,
            result.consumption.assignment.assignmentId, UUID.nameUUIDFromBytes("warehouse:$operation".toByteArray(Charsets.UTF_8)), operation,
            Math.addExact(binding.revisions.useRevision, 1), result.key, result.hash, mapper.writeValueAsString(result.consumption), result.consumption.createsOnu)
        Unit
    }

    fun installedLocation(): UUID = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_location WHERE tenant_id=? AND code='CUSTOMER_INSTALLED' AND kind='CUSTOMER_SITE' AND state='ACTIVE' FOR SHARE", sql.tenant) {
            it.uuid("id")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun assertOwnership(source: DeploymentSource, mode: AssetOwnershipMode) = jdbc.execute { sql ->
        if (sql.value("""SELECT asset.id FROM inventory_serialized_asset asset JOIN inventory_document_line origin
            ON origin.tenant_id=asset.tenant_id AND origin.id=asset.origin_document_line_id
            JOIN inventory_receipt_intake intake ON intake.tenant_id=origin.tenant_id AND intake.id=origin.document_id
            WHERE asset.tenant_id=? AND asset.id=? AND EXISTS(SELECT FROM jsonb_array_elements(intake.snapshot::jsonb->'lines') item
                WHERE item#>>'{sku,id}'=asset.sku_id::text AND jsonb_exists(item#>'{sku,allowedOwnershipModes}',?))""",
                sql.tenant, source.custody.stockIdentityId, mode.name) == null) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (sql.value("SELECT id FROM inventory_sku WHERE tenant_id=? AND id=? AND state='ACTIVE' AND ?=ANY(allowed_ownership_modes) FOR SHARE",
                sql.tenant, source.custody.skuId, mode.name) == null) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun useRevision(workOrder: UUID): Long = totals.useRevision(workOrder)

    fun previousRevision(assignment: UUID, customer: UUID): Long = jdbc.execute { sql ->
        sql.value("SELECT revision FROM inventory_asset_assignment WHERE tenant_id=? AND id=? AND customer_id=? AND ended_at IS NULL AND warehouse_admission='VERIFIED'",
            sql.tenant, assignment, customer)?.toLong() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockPrevious(binding: DeploymentBinding) = jdbc.execute { sql ->
        binding.previousAssignmentId?.let { assignment ->
            val oldAsset = sql.value("""SELECT asset_id FROM inventory_asset_assignment WHERE tenant_id=? AND id=? AND customer_id=?
                AND revision=? AND ended_at IS NULL AND warehouse_admission='VERIFIED' FOR UPDATE""",
                sql.tenant, assignment, binding.customerId, binding.previousAssignmentRevision) ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            if (oldAsset == binding.assetId.toString()) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            sql.query("SELECT id FROM inventory_serialized_asset WHERE tenant_id=? AND id IN (?,?) ORDER BY id FOR UPDATE",
                sql.tenant, UUID.fromString(oldAsset), binding.assetId) { it.uuid("id") }
            sql.value("SELECT warehouse_assert_current_asset_title(?,?)", sql.tenant, assignment)
        }
        Unit
    }

    fun history(assetId: UUID): List<DeploymentPermit> = jdbc.execute { sql ->
        sql.query("""SELECT permit.id FROM inventory_deployment_authorization permit JOIN inventory_deployment_result result
            ON result.tenant_id=permit.tenant_id AND result.authorization_id=permit.id
            WHERE permit.tenant_id=? AND permit.asset_id=? ORDER BY permit.created_at,permit.id""", sql.tenant, assetId) {
            read(it.uuid("id"))
        }
    }
}
