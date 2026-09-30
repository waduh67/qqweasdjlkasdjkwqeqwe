package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class CustomerAssetWorkbenchQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun sources(workOrder: UUID, actor: UUID, page: WarehousePageRequest, access: WarehouseQueryAccess, assetId: UUID?): WarehousePage<CustomerAssetSource> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page.page, size = page.size), access)
        decode(query.result(MaterialCustodyQuerySql.rows + query.page("""SELECT live.*,asset.revision asset_revision,origin.kind provenance,modes.allowed
            FROM live_sources live JOIN inventory_serialized_asset asset ON asset.tenant_id=(SELECT tenant FROM request) AND asset.id=live.id
            JOIN inventory_sku catalog ON catalog.tenant_id=asset.tenant_id AND catalog.id=asset.sku_id
            JOIN inventory_document_line origin_line ON origin_line.tenant_id=asset.tenant_id AND origin_line.id=asset.origin_document_line_id
            JOIN inventory_document origin ON origin.tenant_id=origin_line.tenant_id AND origin.id=origin_line.document_id
            JOIN inventory_receipt_intake intake ON intake.tenant_id=origin.tenant_id AND intake.id=origin.id
            JOIN visible_locations location ON location.id=live.location_id
            CROSS JOIN LATERAL (SELECT jsonb_agg(mode ORDER BY mode) allowed FROM unnest(catalog.allowed_ownership_modes) mode
                WHERE mode IN ('LOAN','SALE') AND EXISTS(SELECT FROM jsonb_array_elements(intake.snapshot::jsonb->'lines') line
                    WHERE line#>>'{sku,id}'=catalog.id::text AND jsonb_exists(line#>'{sku,allowedOwnershipModes}',mode))) modes
            WHERE live.initial_use_source AND live.base_unit='EA' AND live.quantity_base=1 AND live.serial_number IS NOT NULL
                AND asset.warehouse_admission='VERIFIED' AND asset.status='ISSUED' AND asset.condition='SERVICEABLE' AND asset.legal_owner='ISP'
                AND asset.custody_owner_kind='TECHNICIAN' AND asset.custody_owner_id=live.custodian_id AND asset.location_id=live.location_id
                AND catalog.state='ACTIVE' AND catalog.tracking='SERIAL' AND location.state='ACTIVE' AND modes.allowed IS NOT NULL
                AND NOT EXISTS(SELECT FROM inventory_asset_assignment assignment WHERE assignment.tenant_id=asset.tenant_id AND assignment.asset_id=asset.id AND assignment.ended_at IS NULL)
                AND (?::uuid IS NULL OR live.id=?::uuid)""",
            """jsonb_build_object('id',id,'source',${MaterialCustodyQuerySql.body},'assetRevision',asset_revision,'provenance',provenance,'ownershipModes',allowed)""", "issue_code"),
            workOrder, actor, assetId, assetId), CustomerAssetSource::class.java)
    }
    fun history(customerId: UUID, page: WarehousePageRequest, assignmentId: UUID?): WarehousePage<CustomerAssetAssignmentView> = jdbc.execute { sql ->
        val predicate = "assignment.tenant_id=? AND assignment.customer_id=? AND assignment.warehouse_admission='VERIFIED' AND (?::uuid IS NULL OR assignment.id=?::uuid)"
        val total = sql.value("SELECT count(*) FROM inventory_asset_assignment assignment WHERE $predicate", sql.tenant, customerId, assignmentId, assignmentId)!!.toLong()
        val rows = sql.query("""SELECT assignment.*,asset.canonical_serial,sku.id sku_id,sku.revision sku_revision,sku.code sku_code,sku.name sku_name,
            document.id issue_id,document.code issue_code,origin.id origin_id,origin.code origin_code,origin.kind origin_kind,
            handover.id handover_id,(CASE WHEN handover.ownership_mode='SALE' AND assignment.purpose<>'RETURN_CUSTOMER_RMA' THEN 1 ELSE 0 END)+
                (SELECT count(*) FROM inventory_asset_title_transfer WHERE tenant_id=assignment.tenant_id AND assignment_id=assignment.id) title_revision,
            CASE WHEN assignment.ended_at IS NULL THEN asset.status ELSE 'RETIRED' END position_status,
            assignment.legal_owner='ISP' AND (assignment.ownership_mode='LOAN' OR EXISTS(SELECT FROM inventory_asset_title_transfer
                WHERE tenant_id=assignment.tenant_id AND assignment_id=assignment.id)) recovery_required
            FROM inventory_asset_assignment assignment JOIN inventory_serialized_asset asset ON asset.tenant_id=assignment.tenant_id AND asset.id=assignment.asset_id
            JOIN inventory_sku sku ON sku.tenant_id=asset.tenant_id AND sku.id=asset.sku_id
            LEFT JOIN inventory_issue_line issued ON issued.tenant_id=assignment.tenant_id AND issued.id=assignment.issue_line_id
            LEFT JOIN inventory_document document ON document.tenant_id=issued.tenant_id AND document.id=issued.issue_id
            LEFT JOIN inventory_document_line origin_line ON origin_line.tenant_id=asset.tenant_id AND origin_line.id=asset.origin_document_line_id
            LEFT JOIN inventory_document origin ON origin.tenant_id=origin_line.tenant_id AND origin.id=origin_line.document_id
            LEFT JOIN inventory_asset_handover handover ON handover.tenant_id=assignment.tenant_id AND handover.assignment_id=assignment.id
            WHERE $predicate ORDER BY assignment.started_at DESC,assignment.id LIMIT ${page.size} OFFSET ${page.page.toLong() * page.size}""",
            sql.tenant, customerId, assignmentId, assignmentId) { row ->
            CustomerAssetAssignmentView(row.uuid("id"), row.uuid("customer_id"), row.uuid("asset_id"), row.getString("canonical_serial"),
                MaterialSkuSnapshot(row.uuid("sku_id"), row.getLong("sku_revision"), row.getString("sku_code"), row.getString("sku_name"), WarehouseTracking.SERIAL, WarehouseBaseUnit.EA),
                row.uuid("work_order_id"), row.optionalUuid("issue_id"), row.getString("issue_code"), row.getLong("revision"), row.getLong("title_revision"),
                DeploymentPurpose.valueOf(row.getString("purpose")), AssetProvenance.valueOf(row.getString("provenance")), AssetOwnershipMode.valueOf(row.getString("ownership_mode")),
                AssetLegalOwner.valueOf(row.getString("legal_owner")), if (row.optionalUuid("handover_id") == null) AssetHandoverState.PENDING else AssetHandoverState.ACCEPTED,
                row.getTimestamp("started_at").toInstant(), row.getTimestamp("ended_at")?.toInstant(), row.optionalUuid("previous_assignment_id"),
                row.optionalUuid("origin_id")?.let { CustomerAssetOrigin(it, row.getString("origin_code"), row.getString("origin_kind")) },
                row.getString("position_status"), row.getBoolean("recovery_required"))
        }
        WarehousePage(rows, page.page, page.size, total)
    }
    private fun <T : Any> decode(body: String, type: Class<T>): WarehousePage<T> {
        val value = mapper.readTree(body)
        return WarehousePage(value.path("items").asSequence().map { mapper.readValue(it.toString(), type) }.toList(), value.path("page").asInt(), value.path("size").asInt(), value.path("totalElements").asLong())
    }
}
