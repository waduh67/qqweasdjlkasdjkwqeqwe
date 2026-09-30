package com.duluin.ftth.inventory

import java.sql.Connection
import java.util.UUID

internal class WarehouseLotCapacityFixture(val database: WarehouseSchemaDatabase) {
    val tenant = UUID.randomUUID()
    val location = UUID.randomUUID()
    val sku = UUID.randomUUID()
    val document = UUID.randomUUID()
    val line = UUID.randomUUID()
    val lot = UUID.randomUUID()
    val firstRoot = UUID.randomUUID()

    fun open(): Connection = database.dataSource.connection.apply {
        autoCommit = false
        sql("SET LOCAL app.tenant_id='$tenant'")
        sql("SET LOCAL lock_timeout='15s'")
        sql("SET LOCAL statement_timeout='20s'")
        check(value("SELECT current_user") == "warehouse_app")
    }

    fun create(firstQuantity: Long) {
        open().use { connection ->
            connection.sql("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','capacity-$tenant','Capacity')")
            connection.sql("INSERT INTO inventory_location(id,tenant_id,code,kind,issue_eligible) VALUES ('$location','$tenant','bin','BIN',true)")
            connection.sql("INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('$sku','$tenant','cable','Cable','LOT','MM')")
            connection.sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','receipt','RECEIPT','$location',0,0)")
            connection.sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'$sku','MM','LOT',100,'$location','$location','WAREHOUSE','SERVICEABLE','ISP')")
            connection.sql("INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id) VALUES ('$lot','$tenant','$sku','lot','MM',100,now(),'$line')")
            addPiece(connection, firstQuantity, firstRoot)
            connection.sql("UPDATE inventory_document_line SET stock_identity_id='$firstRoot',lot_id='$lot',revision=1 WHERE id='$line'")
            connection.sql("UPDATE inventory_document SET state='RECEIVED_IN_INSPECTION',revision=1 WHERE id='$document'")
            connection.commit()
        }
    }

    fun addPiece(connection: Connection, quantity: Long, identity: UUID = UUID.randomUUID(), parent: UUID? = null) {
        val parentSql = parent?.let { "'$it'" } ?: "NULL"
        connection.sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base) VALUES ('$identity','$tenant','$sku','$lot',$parentSql,'REEL','MM',$quantity)")
        connection.sql("INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,rebuilt_at,quantity_base,base_unit,stock_identity_id,lot_id,condition,legal_owner) VALUES ('${UUID.randomUUID()}','$tenant','$identity','$sku','$location','$location','WAREHOUSE','AVAILABLE',now(),$quantity,'MM','$identity','$lot','SERVICEABLE','ISP')")
        connection.sql("INSERT INTO inventory_reservation(id,tenant_id,document_line_id,sku_id,stock_identity_id,lot_id,base_unit,location_id,custodian_id,custodian_kind,condition,legal_owner,reserved_unpicked_base,reserved_picked_base,submitted_at,expires_at) VALUES ('${UUID.randomUUID()}','$tenant','$line','$sku','$identity','$lot','MM','$location','$location','WAREHOUSE','SERVICEABLE','ISP',$quantity,0,now(),now()+interval '1 day')")
    }

    fun retireOrSplit(state: String) {
        open().use { connection ->
            connection.sql("UPDATE inventory_segment SET state='$state',revision=revision+1 WHERE id='$firstRoot'")
            if (state == "SPLIT") {
                addPiece(connection,40,parent=firstRoot)
                addPiece(connection,60,parent=firstRoot)
            }
            connection.sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE stock_identity_id='$firstRoot'")
            connection.sql("UPDATE inventory_reservation SET reserved_unpicked_base=0,reserved_picked_base=0,state='RELEASED',revision=revision+1 WHERE stock_identity_id='$firstRoot'")
            connection.commit()
        }
    }

    fun totals(): List<Long> = open().use { connection -> listOf(
        connection.value("SELECT coalesce(sum(quantity_base::numeric),0) FROM inventory_segment WHERE lot_id='$lot' AND warehouse_admission='VERIFIED' AND parent_segment_id IS NULL").toLong(),
        connection.value("SELECT coalesce(sum(quantity_base::numeric),0) FROM inventory_balance_projection WHERE lot_id='$lot' AND warehouse_admission='VERIFIED'").toLong(),
        connection.value("SELECT coalesce(sum(reserved_unpicked_base::numeric+reserved_picked_base::numeric),0) FROM inventory_reservation WHERE lot_id='$lot' AND state='OPEN'").toLong(),
    ) }

    companion object {
        fun Connection.sql(sql: String) { createStatement().use { it.execute(sql) } }
        fun Connection.value(sql: String): String = createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
        }
    }
}
