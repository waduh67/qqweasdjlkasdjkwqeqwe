package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import java.util.UUID

/** The retained HTTP DTOs share the canonical warehouse visibility predicate. */
@Repository
class WarehouseLegacyQueries(private val jdbc: WarehouseCommandJdbc) {
    fun locations(access: WarehouseQueryAccess): String = query(access, """
        SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'code',code,'kind',kind) ORDER BY id),'[]'::jsonb)::text
        FROM visible_locations""")

    fun items(access: WarehouseQueryAccess): String = query(access, """
        SELECT coalesce(jsonb_agg(jsonb_build_object('id',asset.id,'skuId',asset.sku_id,
            'serialNumber',asset.serial_number,'macAddress',asset.mac_address,'status',asset.status) ORDER BY asset.id),'[]'::jsonb)::text
        FROM inventory_serialized_asset asset,request
        WHERE asset.tenant_id=request.tenant AND asset.location_id IN (SELECT id FROM visible_locations)""")

    fun custody(access: WarehouseQueryAccess): String = query(access, """
        SELECT coalesce(jsonb_agg(jsonb_build_object('assetId',asset.id,'skuId',asset.sku_id,'status',asset.status,
            'ownerKind',asset.custody_owner_kind,'ownerId',asset.custody_owner_id,'locationId',asset.location_id)
            ORDER BY asset.id),'[]'::jsonb)::text
        FROM inventory_serialized_asset asset,request
        WHERE asset.tenant_id=request.tenant AND asset.location_id IN (SELECT id FROM visible_locations)""")

    fun reservations(access: WarehouseQueryAccess): String = query(access, """
        SELECT coalesce(jsonb_agg(jsonb_build_object('assetId',asset.id,'skuId',asset.sku_id,
            'locationId',asset.location_id,'custodianId',asset.custody_owner_id) ORDER BY asset.id),'[]'::jsonb)::text
        FROM inventory_serialized_asset asset,request
        WHERE asset.tenant_id=request.tenant AND asset.location_id IN (SELECT id FROM visible_locations)
        AND EXISTS (SELECT FROM inventory_reservation reservation
            WHERE reservation.tenant_id=asset.tenant_id AND reservation.stock_identity_id=asset.id
            AND reservation.location_id=asset.location_id AND reservation.custodian_id=asset.custody_owner_id
            AND reservation.custodian_kind=asset.custody_owner_kind AND reservation.base_unit='EA'
            AND reservation.lot_id IS NULL AND reservation.state='OPEN'
            AND (reservation.reserved_unpicked_base>0 OR reservation.reserved_picked_base>0))""")

    fun asset(access: WarehouseQueryAccess, id: UUID): String = query(access, """
        SELECT jsonb_build_object('assetId',asset.id,'tenantId',asset.tenant_id,'skuId',asset.sku_id,
            'serialNumber',asset.serial_number,'macAddress',asset.mac_address,'status',asset.status,
            'locationId',asset.location_id,'custodyOwnerId',asset.custody_owner_id,'installedOnuId',asset.installed_onu_id)::text
        FROM inventory_serialized_asset asset,request
        WHERE asset.tenant_id=request.tenant AND asset.location_id IN (SELECT id FROM visible_locations) AND asset.id=?""", id)

    private fun query(access: WarehouseQueryAccess, statement: String, vararg values: Any?): String = jdbc.execute { sql ->
        WarehouseQuerySql(sql, WarehouseQueryFilter(), access).result(statement, *values)
    }
}
