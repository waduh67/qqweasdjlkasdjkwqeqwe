package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class RmaDeploymentStore(private val jdbc: WarehouseCommandJdbc, private val deployments: DeploymentStore) {
    private val mapper = jacksonObjectMapper()
    fun source(repairCaseId: UUID): RmaDeploymentSource = jdbc.execute { sql ->
        sql.query("""SELECT handover.id,document.revision handover_revision,repair.id repair_id,repair.revision repair_revision,
            handover.original_assignment_id,assignment.revision original_revision,handover.customer_id,handover.work_order_id,
            asset.id asset_id,asset.sku_id,asset.location_id,asset.custody_owner_id,asset.revision asset_revision,
            asset.canonical_serial,sku.model,sku.category,origin.kind provenance
            FROM inventory_rma_handover handover JOIN inventory_rma_receipt receipt
                ON receipt.tenant_id=handover.tenant_id AND receipt.handover_id=handover.id
            JOIN inventory_document document ON document.tenant_id=handover.tenant_id AND document.id=handover.id
            JOIN inventory_repair_case repair ON repair.tenant_id=handover.tenant_id AND repair.id=handover.repair_case_id
            JOIN inventory_asset_assignment assignment ON assignment.tenant_id=handover.tenant_id AND assignment.id=handover.original_assignment_id
            JOIN inventory_serialized_asset asset ON asset.tenant_id=handover.tenant_id AND asset.id=handover.asset_id
            JOIN inventory_sku sku ON sku.tenant_id=asset.tenant_id AND sku.id=asset.sku_id
            JOIN inventory_document_line line ON line.tenant_id=asset.tenant_id AND line.id=asset.origin_document_line_id
            JOIN inventory_document origin ON origin.tenant_id=line.tenant_id AND origin.id=line.document_id
            WHERE handover.tenant_id=? AND handover.repair_case_id=? AND document.state='RECEIVED' AND document.revision=2
                AND asset.warehouse_admission='VERIFIED' AND asset.legal_owner='CUSTOMER' AND asset.condition='SERVICEABLE'
                AND asset.status='ISSUED' AND asset.custody_owner_kind='TECHNICIAN' AND asset.custody_owner_id=handover.technician_id
                AND asset.location_id=(handover.body::jsonb#>>'{request,technicianLocationId}')::uuid
                AND sku.state='ACTIVE' AND sku.tracking='SERIAL' AND sku.base_unit='EA' AND assignment.ended_at IS NOT NULL""",
            sql.tenant, repairCaseId) {
            RmaDeploymentSource(it.uuid("id"), it.getLong("handover_revision"), it.uuid("repair_id"), it.getLong("repair_revision"),
                it.uuid("original_assignment_id"), it.getLong("original_revision"), it.uuid("customer_id"), it.uuid("work_order_id"),
                PostingDimension(it.uuid("sku_id"), it.uuid("asset_id"), null, it.uuid("location_id"), it.uuid("custody_owner_id"),
                    OwnerKind.TECHNICIAN, WarehouseCondition.SERVICEABLE, AssetLegalOwner.CUSTOMER), it.getLong("asset_revision"),
                it.getString("canonical_serial"), it.getString("model"), it.getString("category") in setOf("ONU", "ONT"),
                AssetProvenance.valueOf(it.getString("provenance")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockSource(source: RmaDeploymentSource) = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_document WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, source.handoverId)
        sql.value("SELECT id FROM inventory_serialized_asset WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, source.custody.stockIdentityId)
        if (this.source(source.repairCaseId) != source) sql.fail(WarehouseErrorCode.STALE_REVISION)
        if (sql.value("SELECT id FROM inventory_asset_assignment WHERE tenant_id=? AND asset_id=? AND ended_at IS NULL",
                sql.tenant, source.custody.stockIdentityId) != null) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (sql.value("""SELECT quantity_base FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=?
            AND location_id=? AND custody_owner_id=? AND custody_owner_kind='TECHNICIAN' AND condition='SERVICEABLE'
            AND legal_owner='CUSTOMER' AND status='ISSUED' AND quantity_base=1 FOR UPDATE""", sql.tenant,
                source.custody.stockIdentityId, source.custody.locationId, source.custody.custodianId) != "1")
            sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
        sql.value("SELECT warehouse_assert_rma_handover(?,?)", sql.tenant, source.handoverId)
        Unit
    }

    fun preview(id: UUID): RmaDeploymentPermit = jdbc.execute { sql ->
        sql.query("""SELECT permit.consumed,execution.binding,execution.source FROM inventory_deployment_authorization permit
            JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
            WHERE permit.tenant_id=? AND permit.id=? AND permit.purpose='RETURN_CUSTOMER_RMA'""", sql.tenant, id) {
            RmaDeploymentPermit(mapper.readValue(it.getString("binding"), DeploymentBinding::class.java),
                mapper.readValue(it.getString("source"), RmaDeploymentSource::class.java), it.getBoolean("consumed"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun read(id: UUID): RmaDeploymentPermit = jdbc.execute { sql ->
        sql.value("SELECT id FROM warehouse_read_deployment_authorization(?,?)", sql.tenant, id)
        preview(id)
    }
    fun lock(id: UUID): RmaDeploymentPermit = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_deployment_authorization WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id)
        preview(id)
    }
    fun findMint(key: String): RmaDeploymentMint? = jdbc.execute { sql ->
        sql.query("""SELECT authorization_id,mint_hash FROM inventory_deployment_execution
            WHERE tenant_id=? AND mint_key=? AND rma_handover_id IS NOT NULL""", sql.tenant, key) {
            RmaDeploymentMint(preview(it.uuid("authorization_id")), key, it.getString("mint_hash"))
        }.singleOrNull()
    }
    fun mint(mint: RmaDeploymentMint) = jdbc.execute { sql ->
        val binding = mint.permit.binding
        deployments.mintAuthorization(binding)
        sql.update("""INSERT INTO inventory_deployment_execution(tenant_id,authorization_id,use_revision,rma_handover_id,
            mint_key,mint_hash,binding,source) VALUES (?,?,?,?,?,?,?,?)""", sql.tenant, binding.authorizationId,
            binding.revisions.useRevision, mint.permit.source.handoverId, mint.key, mint.hash,
            mapper.writeValueAsString(binding), mapper.writeValueAsString(mint.permit.source))
        Unit
    }
}
