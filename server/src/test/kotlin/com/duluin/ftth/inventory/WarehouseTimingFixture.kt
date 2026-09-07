package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.WarehouseLotCapacityFixture.Companion.sql
import java.sql.Connection
import java.util.UUID

internal enum class WarehouseTimingFamily(val constraint: String, val function: String, val serial: Boolean = false, val invalidState: String = "23514") {
    PROVENANCE("warehouse_verified_provenance", "warehouse_stock_provenance_guard"),
    SOURCE("warehouse_verified_source", "warehouse_source_provenance_guard", true),
    CLAIM("warehouse_asset_claim", "warehouse_asset_claim_guard", true),
    ORIGIN("warehouse_lot_origin", "warehouse_origin_guard"),
    CONSERVATION("warehouse_segment_conservation", "warehouse_segment_conservation"),
    USAGE("warehouse_usage_postings", "warehouse_usage_postings_guard", invalidState = "23503"),
    CAPACITY("warehouse_lot_root_capacity", "warehouse_check_lot_root_capacity"),
    SCOPE("warehouse_aaa_deferred_tenant_scope", "warehouse_deferred_scope_guard"),
}

internal class WarehouseTimingFixture(database: WarehouseSchemaDatabase, serial: Boolean) : AutoCloseable {
    val connection: Connection = database.dataSource.connection.apply { autoCommit = false }
    val stock = WarehouseSchemaFixture(connection)
    val claim: UUID = UUID.randomUUID()

    init {
        if (serial) serialReceipt() else stock.receipt()
        stock.sql(stock.balance(quantity = if (serial) "1" else "40")
            .let { if (serial) it.replace("'${stock.lot}'", "NULL").replace("'MM'", "'EA'") else it })
        connection.commit()
        stock.sql("SET LOCAL app.tenant_id='${stock.tenant}'")
    }

    fun prepare(family: WarehouseTimingFamily, valid: Boolean) = with(stock) {
        when (family) {
            WarehouseTimingFamily.PROVENANCE -> {
                if (valid) sql("UPDATE inventory_balance_projection SET quantity_base=39,revision=1 WHERE stock_identity_id='$segment'")
                else {
                    sql("UPDATE inventory_segment SET state='RETIRED',revision=1 WHERE id='$segment'")
                    sql("UPDATE inventory_balance_projection SET revision=1 WHERE stock_identity_id='$segment'")
                }
            }
            WarehouseTimingFamily.SOURCE -> sql("UPDATE inventory_identity_claim SET state='${if (valid) "ADMITTED" else "RETIRED"}',revision=1 WHERE id='$claim'")
            WarehouseTimingFamily.CLAIM -> {
                if (valid) sql("UPDATE inventory_serialized_asset SET status='ISSUED',revision=1 WHERE id='$segment'")
                else asset(UUID.randomUUID(), "UNCLAIMED")
            }
            WarehouseTimingFamily.ORIGIN -> {
                val targetSku = if (valid) sku else UUID.randomUUID().also {
                    sql("INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('$it','$tenant','other','Other','LOT','MM')")
                }
                sql("INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id) VALUES ('${UUID.randomUUID()}','$tenant','$targetSku','extra','MM',1,now(),'$line')")
            }
            WarehouseTimingFamily.CONSERVATION -> {
                sql("UPDATE inventory_segment SET state='SPLIT',revision=1 WHERE id='$segment'")
                sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=1 WHERE stock_identity_id='$segment'")
                sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base) VALUES ('${UUID.randomUUID()}','$tenant','$sku','$lot','$segment','CUT','MM',${if (valid) 82500 else 1})")
            }
            WarehouseTimingFamily.USAGE -> usage(valid)
            WarehouseTimingFamily.CAPACITY -> {
                if (valid) sql("UPDATE inventory_segment SET revision=1 WHERE id='$segment'")
                else sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base) VALUES ('${UUID.randomUUID()}','$tenant','$sku','$lot','REEL','MM',1)")
            }
            WarehouseTimingFamily.SCOPE -> sql("UPDATE inventory_balance_projection SET revision=1 WHERE stock_identity_id='$segment'")
        }
    }

    fun holdOnly(constraint: String) {
        val early = connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT DISTINCT tgname FROM pg_trigger JOIN pg_class ON pg_class.oid=tgrelid
                WHERE relnamespace=current_schema()::regnamespace AND tgdeferrable AND NOT tgisinternal
                  AND tgname LIKE 'warehouse_%' ORDER BY tgname
            """.trimIndent()).use { rows -> buildList {
                while (rows.next()) if (rows.getString(1) != constraint) add(rows.getString(1))
            } }
        }
        check(early.isNotEmpty())
        stock.sql("SET CONSTRAINTS ${early.joinToString(",")} IMMEDIATE")
    }

    private fun serialReceipt() = with(stock) {
        masters()
        sql("UPDATE inventory_sku SET tracking='SERIAL',base_unit='EA',revision=1 WHERE id='$sku'")
        sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','serial','RECEIPT','$actor',0,0)")
        sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'$sku','EA','SERIAL',1,'$location','$actor','TECHNICIAN','QUARANTINE','ISP')")
        sql("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES ('$claim','$tenant','SERIAL','SERIAL-1','ADMITTED','$segment')")
        asset(segment,"SERIAL-1")
        sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,asset_id,kind,base_unit,quantity_base) VALUES ('$segment','$tenant','$sku','$segment','SERIAL','EA',1)")
        sql("UPDATE inventory_document_line SET stock_identity_id='$segment',revision=1 WHERE id='$line'")
        sql("UPDATE inventory_document SET state='RECEIVED_IN_INSPECTION',revision=1 WHERE id='$document'")
    }

    private fun asset(id: UUID, serial: String) = with(stock) {
        sql("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,warehouse_sku_id,serial_number,canonical_serial,status,location_id,custody_owner_id,custody_owner_kind,quantity_base,base_unit,condition,legal_owner,origin_document_line_id) VALUES ('$id','$tenant','$sku','$sku','$serial','$serial','QUARANTINE','$location','$actor','TECHNICIAN',1,'EA','QUARANTINE','ISP','$line')")
    }

    private fun usage(valid: Boolean) = with(stock) {
        val operation = operation()
        val plan = UUID.randomUUID()
        val workOrder = UUID.randomUUID()
        val posting = UUID.randomUUID()
        sql("INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id) VALUES ('$plan','$tenant','$workOrder',1,0,'MATERIAL_REQUIRED','$actor')")
        sql("INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit) VALUES ('${UUID.randomUUID()}','$tenant','$plan',1,'$sku',1,'MM')")
        sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=now(),revision=1 WHERE id='$plan'")
        if (valid) sql("INSERT INTO inventory_movement(id,tenant_id,operation_namespace,operation_key,payload_hash,actor_id,reason,server_received_at,kind,state,document_id,document_revision,operation_id) VALUES ('$posting','$tenant','use','use','${"a".repeat(64)}','$actor','Use',now(),'CONSUME','APPLIED','$document',1,'$operation')")
        sql("INSERT INTO inventory_usage_snapshot(id,tenant_id,work_order_id,use_revision,plan_id,work_order_revision,operation_id,posting_ids,frozen_snapshot) VALUES ('${UUID.randomUUID()}','$tenant','$workOrder',1,'$plan',0,'$operation',ARRAY['$posting'::uuid],'{}')")
    }

    override fun close() = connection.close()
}
