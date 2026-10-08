package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.TransferLine
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReferenceWarehouseStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun review(): String = jdbc.execute { sql -> requireNotNull(sql.value("SELECT warehouse_reference_review(?)::text", sql.tenant)) }

    fun activation(key: String): Pair<UUID, Pair<String, String>>? = jdbc.execute { sql ->
        sql.query("SELECT actor_id,payload_hash,original_body FROM inventory_reference_activation WHERE tenant_id=? AND operation_key=?",
            sql.tenant, key) { it.uuid("actor_id") to (it.getString("payload_hash") to it.getString("original_body")) }.singleOrNull()
    }

    fun activate(actor: UUID, authority: Long, key: String, canonical: String): String = jdbc.execute { sql ->
        requireNotNull(sql.value("SELECT warehouse_activate_reference(?,?,?,?)", actor, authority, key, canonical))
    }

    fun receiptBoundary(): UUID = jdbc.execute { sql ->
        val existing = sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND code='RECEIPT_SOURCE' AND kind='TRANSIT' AND state='ACTIVE'",
            sql.tenant)?.let(UUID::fromString)
        existing ?: UUID.randomUUID().also { id ->
            sql.update("INSERT INTO inventory_location(id,tenant_id,code,name,kind) VALUES (?,?,'RECEIPT_SOURCE','Penerimaan barang','TRANSIT')", id, sql.tenant)
        }
    }

    fun receiptSupplier(): UUID = jdbc.execute { sql ->
        val existing = sql.value("SELECT id FROM inventory_supplier WHERE tenant_id=? AND code='REFERENCE-INTERNAL' AND state='ACTIVE'", sql.tenant)
            ?.let(UUID::fromString)
        existing ?: UUID.randomUUID().also { id ->
            sql.update("INSERT INTO inventory_supplier(id,tenant_id,code,name) VALUES (?,?,'REFERENCE-INTERNAL','Penerimaan langsung')", id, sql.tenant)
        }
    }

    fun technicianLocation(technician: UUID, name: String): UUID = jdbc.execute { sql ->
        val locations = sql.query("SELECT id FROM inventory_location WHERE tenant_id=? AND custodian_id=? AND kind='TECHNICIAN' AND state='ACTIVE' ORDER BY id FOR UPDATE",
            sql.tenant, technician) { it.uuid("id") }
        if (locations.size > 1) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        locations.singleOrNull() ?: UUID.randomUUID().also { id ->
            sql.update("INSERT INTO inventory_location(id,tenant_id,code,name,kind,custodian_id) VALUES (?,?,?,?,'TECHNICIAN',?)",
                id, sql.tenant, "TECH-$technician", name, technician)
        }
    }

    fun transferDraft(id: UUID, source: UUID, destination: UUID, lines: List<TransferLine>, actor: UUID, authority: Long, epoch: Long, notes: String) =
        jdbc.execute { sql ->
            sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch,reason)
                VALUES (?,?,?,'TRANSFER',?,?,?,?)""", id, sql.tenant, "TRF-$id", actor, epoch, authority, notes)
            lines.forEachIndexed { index, line ->
                sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,stock_identity_id,lot_id,
                    base_unit,tracking,quantity_base,location_id,destination_location_id,custodian_id,custodian_kind,condition,legal_owner)
                    VALUES (?,?,?,0,?,?,?,?,?,?,?,?,?,?,'WAREHOUSE','SERVICEABLE','ISP')""", line.id, sql.tenant, id, index + 1,
                    line.source.dimension.skuId, line.source.dimension.stockIdentityId, line.source.dimension.lotId, line.source.unit,
                    line.source.tracking, line.quantity, source, destination, source)
            }
        }

    fun bind(command: WarehousePost, source: UUID, destination: UUID, action: String) = jdbc.execute { sql ->
        val legs = command.legs.map { leg -> mapOf(
            "lineId" to leg.documentLineId, "direction" to leg.direction, "identityId" to leg.dimension.stockIdentityId,
            "skuId" to leg.dimension.skuId, "lotId" to leg.dimension.lotId, "locationId" to leg.dimension.locationId,
            "custodianId" to leg.dimension.custodianId, "custodianKind" to leg.dimension.custodianKind,
            "condition" to leg.dimension.condition, "legalOwner" to leg.dimension.legalOwner,
            "status" to if (leg.endpoint == PostingEndpoint.RECEIPT_SOURCE) "RECEIPT_SOURCE" else leg.status.name,
            "quantityBase" to leg.quantity.quantityBase.toString(), "baseUnit" to leg.quantity.unit) }
        sql.update("""INSERT INTO inventory_reference_post(id,tenant_id,document_id,action,source_location_id,destination_location_id,expected_legs)
            VALUES (?,?,?,?,?,?,?::jsonb)""", command.operation.id, sql.tenant, command.documentId, action, source, destination,
            mapper.writeValueAsString(legs))
    }

    fun positions(sku: UUID): List<ReferenceStockPosition> = jdbc.execute { sql ->
        sql.query("""SELECT balance.*,sku.code sku_code,sku.name sku_name,sku.tracking,location.name location_name,
            location.code location_code,holder.name holder_name,holder.email holder_email,asset.serial_number,asset.mac_address
            FROM inventory_balance_projection balance JOIN inventory_segment segment
                ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
            JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
            JOIN inventory_location location ON location.tenant_id=balance.tenant_id AND location.id=balance.location_id
            LEFT JOIN app_user holder ON holder.tenant_id=balance.tenant_id AND holder.id=balance.custody_owner_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            WHERE balance.tenant_id=? AND balance.sku_id=? AND balance.quantity_base>0 AND balance.warehouse_admission='VERIFIED'
                AND segment.state='ACTIVE' AND balance.status NOT IN ('CONSUMED','CUSTOMER_INSTALLED','DISPOSED')
            ORDER BY location.code,holder.name,segment.id""", sql.tenant, sku) {
            val locationName = it.getString("location_name") ?: it.getString("location_code")
            ReferenceStockPosition(it.uuid("stock_identity_id"), sku, it.getString("sku_code"), it.getString("sku_name"),
                WarehouseTracking.valueOf(it.getString("tracking")), WarehouseBaseUnit.valueOf(it.getString("base_unit")),
                it.getLong("quantity_base").toString(), it.uuid("location_id"), locationName, it.uuid("custody_owner_id"),
                it.getString("holder_name") ?: locationName, it.getString("holder_email"), it.getString("custody_owner_kind"),
                it.getString("status"), it.getString("serial_number"), it.getString("mac_address"), it.getLong("revision"))
        }
    }

    fun history(sku: UUID, locations: Set<UUID>, holder: UUID?, page: Int, size: Int): WarehousePage<ReferenceStockHistory> = jdbc.execute { sql ->
        if (locations.isEmpty()) return@execute WarehousePage(emptyList(), page, size, 0)
        val predicate = """leg.tenant_id=? AND leg.sku_id=? AND leg.location_id IN (${locations.joinToString(",") { "?" }})
            AND movement.warehouse_admission='VERIFIED' AND movement.state='APPLIED' AND leg.status<>'RECEIPT_SOURCE'""" +
            if (holder == null) "" else " AND (leg.custody_owner_kind NOT IN ('TECHNICIAN','VEHICLE') OR leg.custody_owner_id=?)"
        val values = buildList<Any?> { add(sql.tenant); add(sku); addAll(locations); if (holder != null) add(holder) }
        val joins = """FROM inventory_movement_leg leg JOIN inventory_movement movement
            ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            JOIN inventory_location location ON location.tenant_id=leg.tenant_id AND location.id=leg.location_id
            LEFT JOIN app_user actor ON actor.tenant_id=movement.tenant_id AND actor.id=movement.actor_id
            LEFT JOIN app_user holder ON holder.tenant_id=leg.tenant_id AND holder.id=leg.custody_owner_id
            LEFT JOIN inventory_segment segment ON segment.tenant_id=leg.tenant_id AND segment.id=leg.stock_identity_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id"""
        val total = requireNotNull(sql.value("SELECT count(*) $joins WHERE $predicate", *values.toTypedArray())).toLong()
        val rows = sql.query("""SELECT movement.operation_id,movement.document_id,movement.kind,movement.server_received_at,
            coalesce(actor.name,'Pengguna lama') actor_name,movement.reason,leg.location_id,
            coalesce(location.name,location.code) location_name,coalesce(holder.name,location.name,location.code) holder_name,
            leg.direction,leg.quantity_base,leg.base_unit,asset.serial_number,asset.mac_address
            $joins WHERE $predicate ORDER BY movement.server_received_at DESC,movement.id,leg.id LIMIT ? OFFSET ?""",
            *values.toTypedArray(), size, page.toLong() * size) {
            ReferenceStockHistory(it.uuid("operation_id"), it.uuid("document_id"), it.getString("kind"),
                it.getTimestamp("server_received_at").toInstant(), it.getString("actor_name"), it.getString("reason"),
                it.uuid("location_id"), it.getString("location_name"), it.getString("holder_name"), it.getString("direction"),
                it.getLong("quantity_base").toString(), WarehouseBaseUnit.valueOf(it.getString("base_unit")),
                it.getString("serial_number"), it.getString("mac_address"))
        }
        WarehousePage(rows, page, size, total)
    }
}
