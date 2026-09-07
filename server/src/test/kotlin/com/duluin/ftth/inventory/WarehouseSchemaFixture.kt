package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

internal class WarehouseSchemaFixture(val connection: Connection) {
    val tenant: UUID = UUID.randomUUID()
    val location: UUID = UUID.randomUUID()
    val sku: UUID = UUID.randomUUID()
    val document: UUID = UUID.randomUUID()
    val line: UUID = UUID.randomUUID()
    val lot: UUID = UUID.randomUUID()
    val segment: UUID = UUID.randomUUID()
    val actor: UUID = UUID.randomUUID()

    fun sql(sql: String) { connection.createStatement().use { it.execute(sql) } }
    fun scalar(sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
    fun reject(state: String, sql: String) {
        val savepoint = connection.setSavepoint()
        try {
            val failure = assertThrows<SQLException> { sql(sql); sql("SET CONSTRAINTS ALL IMMEDIATE") }
            assertThat(failure.sqlState).isEqualTo(state)
        } finally {
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
    }

    fun masters() {
        assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
        sql("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','schema-$tenant','Schema fixture')")
        sql("SET LOCAL app.tenant_id='$tenant'")
        sql("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$location','$tenant','bin','BIN')")
        sql("INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('$sku','$tenant','cable','Cable','LOT','MM')")
    }

    fun receipt() {
        masters()
        sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','receipt','RECEIPT','$actor',0,0)")
        sql("""
            INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES ('$line','$tenant','$document',1,0,'$sku','MM','LOT',82500,'$location','$actor','TECHNICIAN','QUARANTINE','ISP')
        """.trimIndent())
        sql("""
            INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id)
            VALUES ('$lot','$tenant','$sku','lot','MM',82500,now(),'$line')
        """.trimIndent())
        sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base) VALUES ('$segment','$tenant','$sku','$lot','REEL','MM',82500)")
        sql("UPDATE inventory_document_line SET stock_identity_id='$segment',lot_id='$lot',revision=1 WHERE id='$line'")
        sql("UPDATE inventory_document SET state='RECEIVED_IN_INSPECTION',revision=1 WHERE id='$document'")
        sql("SET CONSTRAINTS ALL IMMEDIATE")
        sql("SET CONSTRAINTS ALL DEFERRED")
    }

    fun operation(): UUID {
        val id = UUID.randomUUID()
        sql("""
            INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch)
            VALUES ('$id','$tenant','receipt.receive','key','$actor','$document','warehouse:$location','${"a".repeat(64)}','$document',1,'RECEIVE',201,'{"state":"received"}',0,0)
        """.trimIndent())
        return id
    }

    fun balance(id: UUID = UUID.randomUUID(), quantity: String = "82500"): String = """
        INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,quantity,rebuilt_at,
            quantity_base,base_unit,stock_identity_id,lot_id,condition,legal_owner)
        VALUES ('$id','$tenant','$segment','$sku','$location','$actor','TECHNICIAN','QUARANTINE',NULL,now(),$quantity,'MM','$segment','$lot','QUARANTINE','ISP')
    """.trimIndent()
}
