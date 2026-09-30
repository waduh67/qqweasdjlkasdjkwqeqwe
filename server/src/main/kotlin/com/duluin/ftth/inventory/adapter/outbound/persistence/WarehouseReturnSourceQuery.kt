package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseReturnSourceQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    /** Eligibility and scope are applied before both counting and pagination. Intake revalidates under its command fence. */
    fun list(filter: WarehouseReturnFilter, access: WarehouseQueryAccess, actorId: UUID): WarehousePage<WarehouseReturnSourceOption> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(filter.page, filter.size, "createdAt", "desc",
            skuId = filter.skuId, serial = filter.serial, locationId = filter.locationId, owner = filter.owner?.name,
            from = filter.from, until = filter.until), access)
        val origins = """, return_sources AS (
            SELECT residual.id,'MATERIAL_RESIDUAL'::text origin,residual.recorded_at created_at,
                residual.transit_identity_id stock_identity_id,residual.quantity_base,residual.base_unit,
                residual.target_location_id location_id,residual.target_location_id quarantine_location_id,
                'WAREHOUSE'::text custodian_kind,residual.target_location_id custodian_id,'ISP'::text legal_owner
            FROM inventory_material_residual residual,request WHERE residual.tenant_id=request.tenant AND residual.purpose='RETURN'
                AND EXISTS (SELECT FROM inventory_material_residual_ack ack WHERE ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id)
            UNION ALL
            SELECT removal.id,'ASSET_REMOVAL'::text,removal.removed_at,removal.asset_id,1::bigint,'EA'::text,
                removal.recovery_location_id,NULL::uuid,'TRANSIT'::text,removal.actor_id,removal.legal_owner
            FROM inventory_asset_removal removal,request WHERE removal.tenant_id=request.tenant AND removal.actor_id<>?::uuid)
        """
        val rows = """SELECT source.id,source.created_at,
                jsonb_build_object('sourceDocumentId',source.id,'origin',source.origin,'code',document.code,
                    'recordedAt',source.created_at,'workOrderId',document.work_order_id,'workOrderCode',document.work_order_code_snapshot,
                    'stockIdentityId',position.stock_identity_id,'lotId',position.lot_id,
                    'item',jsonb_build_object('id',sku.id,'code',sku.code,'name',sku.name,'tracking',sku.tracking,
                        'serial',position.serial_number,'lotCode',lot.code),
                    'quantityBase',position.quantity_base::text,'baseUnit',position.base_unit,'legalOwner',position.legal_owner,
                    'location',jsonb_build_object('id',location.id,'code',location.code,'name',location.name),
                    'quarantineLocationId',source.quarantine_location_id) body
            FROM return_sources source
            JOIN request ON true
            JOIN inventory_document document ON document.tenant_id=request.tenant AND document.id=source.id
            JOIN scoped_positions position ON position.stock_identity_id=source.stock_identity_id
                AND position.location_id=source.location_id AND position.quantity_base=source.quantity_base AND position.base_unit=source.base_unit
                AND position.custody_owner_kind=source.custodian_kind AND position.custody_owner_id=source.custodian_id
                AND position.condition='QUARANTINE' AND position.status='QUARANTINE' AND position.legal_owner=source.legal_owner
                AND position.warehouse_admission='VERIFIED' AND position.segment_state='ACTIVE'
            JOIN inventory_segment segment ON segment.tenant_id=request.tenant AND segment.id=position.stock_identity_id AND segment.warehouse_admission='VERIFIED'
            JOIN inventory_sku sku ON sku.tenant_id=request.tenant AND sku.id=position.sku_id AND sku.state='ACTIVE'
            JOIN visible_locations location ON location.id=source.location_id AND location.state='ACTIVE'
            LEFT JOIN inventory_lot lot ON lot.tenant_id=request.tenant AND lot.id=position.lot_id
            WHERE (source.origin<>'ASSET_REMOVAL' OR (sku.tracking='SERIAL' AND position.serial_number IS NOT NULL))
                AND (source.origin<>'MATERIAL_RESIDUAL' OR (location.kind='QUARANTINE' AND NOT location.issue_eligible))
                AND NOT EXISTS (SELECT FROM inventory_return_case intake WHERE intake.tenant_id=request.tenant
                    AND intake.origin=source.origin AND intake.source_document_id=source.id)
                AND (request.sku IS NULL OR sku.id=request.sku)
                AND (request.serial IS NULL OR warehouse_canonical_serial(position.serial_number)=request.serial)
                AND (request.location IS NULL OR source.location_id=request.location)
                AND (request.owner IS NULL OR source.legal_owner=request.owner)
                AND (request.since IS NULL OR source.created_at>=request.since)
                AND (request.until IS NULL OR source.created_at<request.until)
                AND (?::text IS NULL OR source.origin=?::text)
                AND (?::uuid IS NULL OR source.stock_identity_id=?::uuid)
                AND (?::text IS NULL OR position(lower(?::text) IN lower(concat_ws(' ',document.code,sku.code,sku.name,position.serial_number)))>0)"""
        val result = mapper.readTree(query.result(origins + query.page(rows, "body", "created_at"), actorId,
            filter.origin?.name, filter.origin?.name, filter.stockIdentityId, filter.stockIdentityId, filter.query, filter.query))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseReturnSourceOption::class.java) }.toList(),
            filter.page, filter.size, result.path("totalElements").asLong())
    }
}
