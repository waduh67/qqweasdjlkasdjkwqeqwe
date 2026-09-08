package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.domain.identity.MacIdentity
import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseReceiptOrigins(private val jdbc: WarehouseCommandJdbc) {
    fun admit(record: ReceiptRecord): List<PostingLeg> = jdbc.execute { sql ->
        record.intake.lines.flatMap { line ->
            val identity = UUID.randomUUID()
            val lot = if (line.serial == null) UUID.randomUUID() else null
            val quantity = StockQuantity.parseBase(line.quantityBase, StockUnit.valueOf(line.sku.baseUnit.name))
            if (line.serial != null) {
                val serial = SerialIdentity.parse(line.serial).canonical
                val mac = line.mac?.let { MacIdentity.parse(it).canonical.replace(":", "") }
                for ((type, value) in listOfNotNull("SERIAL" to serial, mac?.let { "MAC" to it }).sortedBy { it.first + it.second }) {
                    sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|receipt-identity|$type|$value")
                    if (sql.value("""SELECT 1 FROM inventory_identity_claim WHERE tenant_id=? AND identity_type=? AND canonical_value=?
                        UNION ALL SELECT 1 FROM inventory_identity_candidate WHERE tenant_id=? AND identity_type=? AND
                        (canonical_value=? OR CASE identity_type WHEN 'SERIAL' THEN warehouse_canonical_serial(raw_value) ELSE warehouse_canonical_mac(raw_value) END=?) LIMIT 1""",
                        sql.tenant, type, value, sql.tenant, type, value, value) != null) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    sql.update("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES (?,?,?,?,'ADMITTED',?)",
                        UUID.randomUUID(), sql.tenant, type, value, identity)
                }
                sql.update("""INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,warehouse_sku_id,serial_number,mac_address,canonical_serial,canonical_mac,
                    status,location_id,custody_owner_id,custody_owner_kind,quantity_base,base_unit,condition,legal_owner,origin_document_line_id)
                    VALUES (?,?,?,?,?,?,?,?,'QUARANTINE',?,?,'WAREHOUSE',1,'EA','QUARANTINE','ISP',?)""", identity, sql.tenant, line.sku.id,
                    line.sku.id, line.serial, line.mac, serial, mac, record.intake.inspection.id, record.intake.inspection.id, line.id)
                sql.update("INSERT INTO inventory_segment(id,tenant_id,sku_id,asset_id,kind,base_unit,quantity_base) VALUES (?,?,?,?,'SERIAL','EA',1)",
                    identity, sql.tenant, line.sku.id, identity)
            } else {
                sql.update("""INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,supplier_id,
                    origin_document_line_id,cost_total_minor,cost_basis_quantity_base,currency) VALUES (?,?,?,?,?,?,clock_timestamp(),?,?,?,?,?)""",
                    lot, sql.tenant, line.sku.id, line.lotCode, line.sku.baseUnit, quantity.quantityBase, record.intake.supplier.id,
                    line.id, line.cost?.totalMinor?.toLong(), line.cost?.costBasisQuantityBase?.toLong(), line.cost?.currency)
                sql.update("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base) VALUES (?,?,?,?,?,?,?)",
                    identity, sql.tenant, line.sku.id, lot, if (line.sku.baseUnit == WarehouseBaseUnit.MM) "REEL" else "BULK", line.sku.baseUnit, quantity.quantityBase)
            }
            sql.update("UPDATE inventory_document_line SET stock_identity_id=?,lot_id=?,revision=revision+1,document_revision=? WHERE tenant_id=? AND id=?",
                identity, lot, record.revision, sql.tenant, line.id)
            val inspection = PostingDimension(line.sku.id, identity, lot, record.intake.inspection.id, record.intake.inspection.id,
                OwnerKind.WAREHOUSE, WarehouseCondition.QUARANTINE, AssetLegalOwner.ISP)
            listOf(PostingLeg(LegDirection.OUT, inspection.copy(locationId = record.intake.source.id, custodianId = record.intake.source.id,
                custodianKind = OwnerKind.TRANSIT), quantity, line.id, InventoryStatus.IN_TRANSIT, PostingEndpoint.RECEIPT_SOURCE),
                PostingLeg(LegDirection.IN, inspection, quantity, line.id, InventoryStatus.QUARANTINE))
        }
    }
}
