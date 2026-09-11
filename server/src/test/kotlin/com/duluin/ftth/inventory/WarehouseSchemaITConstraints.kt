package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.util.UUID
import javax.sql.DataSource

class WarehouseSchemaITConstraints {
    companion object {
        private lateinit var database: WarehouseSchemaDatabase
        @JvmStatic @BeforeAll fun start() { database = WarehouseSchemaDatabase() }
        @JvmStatic @AfterAll fun stop() { database.close() }
    }
    private val dataSource: DataSource get() = database.dataSource

    private fun fixture(receipt: Boolean = true, block: WarehouseSchemaFixture.() -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                WarehouseSchemaFixture(connection).apply { if (receipt) receipt() else masters(); block() }
            } finally { connection.rollback() }
        }
    }

    @Test
    fun `application role cannot cross tenant read write or composite FK`() = fixture {
        val foreign = UUID.randomUUID()
        sql("INSERT INTO tenant(id,slug,name) VALUES ('$foreign','foreign-$foreign','Foreign')")
        sql("SET LOCAL app.tenant_id='$foreign'")
        assertThat(scalar("SELECT count(*) FROM inventory_sku WHERE id='$sku'")).isEqualTo("0")
        reject("42501", "INSERT INTO inventory_supplier(id,tenant_id,code,name) VALUES ('${UUID.randomUUID()}','$tenant','bad','Bad')")
        reject("23503", "INSERT INTO inventory_uom_conversion(id,tenant_id,sku_id,package_unit,numerator,denominator) VALUES ('${UUID.randomUUID()}','$foreign','$sku','box',1,1)")
        sql("SET LOCAL app.tenant_id='$tenant'")
        reject("23514", "UPDATE inventory_sku SET tenant_id='$foreign',revision=1 WHERE id='$sku'")
        sql("SET LOCAL app.tenant_id=''")
        assertThat(scalar("SELECT count(*) FROM inventory_sku")).isEqualTo("0")
    }

    @Test
    fun `exact quantities reject negative overflow fractional and mismatched dimensions`() = fixture {
        reject("23514", balance(quantity = "-1"))
        reject("22003", balance(quantity = "9223372036854775808"))
        reject("22P02", balance(quantity = "'1.5'"))
        reject("23514", balance().replace("'$lot','QUARANTINE'", "NULL,'QUARANTINE'"))
        reject("23503", balance().replace("82500,'MM'", "82500,'EA'"))
        sql(balance())
        assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE tenant_id='$tenant'")).isEqualTo("82500")
        reject("23505", balance())
    }

    @Test
    fun `operation replay identity and business action are unique and immutable`() = fixture {
        val operation = operation()
        reject("23505", """
            INSERT INTO inventory_operation SELECT '${UUID.randomUUID()}',tenant_id,namespace,'different-key',actor_id,resource_id,resource_scope,payload_hash,document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,revision,created_at,updated_at
            FROM inventory_operation WHERE id='$operation'
        """.trimIndent())
        reject("23514", "UPDATE inventory_operation SET original_body='forged',revision=revision+1 WHERE id='$operation'")
        reject("23514", "DELETE FROM inventory_operation WHERE id='$operation'")
        assertThat(scalar("SELECT original_body FROM inventory_operation WHERE id='$operation'")).isEqualTo("{\"state\":\"received\"}")
    }

    @Test
    fun `posted document and lines retain snapshots and SKU unit cannot change`() = fixture {
        reject("23514", "UPDATE inventory_document SET actor_id='${UUID.randomUUID()}',revision=2 WHERE id='$document'")
        reject("23514", "DELETE FROM inventory_document WHERE id='$document'")
        reject("23514", "UPDATE inventory_document_line SET quantity_base=1,revision=2 WHERE id='$line'")
        reject("23514", "UPDATE inventory_sku SET tracking='BULK',revision=1 WHERE id='$sku'")
        reject("23514", "UPDATE inventory_document SET state='DRAFT',revision=2 WHERE id='$document'")
        reject("23514", "UPDATE inventory_sku SET state='ARCHIVED',revision=1 WHERE id='$sku'")
    }

    @Test
    fun `draft updates require exact next revision and master business keys are unique`() = fixture(false) {
        sql("UPDATE inventory_sku SET name='Updated',revision=1 WHERE id='$sku'")
        reject("40001", "UPDATE inventory_sku SET name='Stale',revision=1 WHERE id='$sku'")
        reject("23505", "INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('${UUID.randomUUID()}','$tenant','cable','Other','LOT','MM')")
        reject("23514", "INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('${UUID.randomUUID()}','$tenant','bad','Bad','SERIAL','MM')")
        reject("23514", "INSERT INTO inventory_uom_conversion(id,tenant_id,sku_id,package_unit,numerator,denominator) VALUES ('${UUID.randomUUID()}','$tenant','$sku','box',1,0)")
    }

    @Test
    fun `segment split conserves parent quantity and cannot edit physical identity`() = fixture {
        reject("23514", "UPDATE inventory_segment SET quantity_base=90000,revision=1 WHERE id='$segment'")
        reject("23514", "UPDATE inventory_segment SET state='SPLIT',revision=1 WHERE id='$segment'")
        sql("UPDATE inventory_segment SET state='SPLIT',revision=1 WHERE id='$segment'")
        for (quantity in listOf(80000, 2500)) {
            sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base) VALUES ('${UUID.randomUUID()}','$tenant','$sku','$lot','$segment','CUT','MM',$quantity)")
        }
        sql("SET CONSTRAINTS ALL IMMEDIATE")
        assertThat(scalar("SELECT sum(quantity_base) FROM inventory_segment WHERE parent_segment_id='$segment'")).isEqualTo("82500")
        reject("23514", "DELETE FROM inventory_segment WHERE id='$segment'")
    }

    @Test
    fun `event and inbox receipts are immutable and bound to matching operation`() = fixture {
        val operation = operation()
        val event = UUID.randomUUID()
        sql("INSERT INTO inventory_outbox(id,tenant_id,operation_id,document_id,document_revision,event_kind,payload) VALUES ('$event','$tenant','$operation','$document',1,'RECEIVED','{}')")
        reject("23514", "UPDATE inventory_outbox SET payload='changed',revision=1 WHERE id='$event'")
        reject("23514", "DELETE FROM inventory_outbox WHERE id='$event'")
        val inbox = UUID.randomUUID()
        sql("INSERT INTO inventory_inbox(id,tenant_id,event_id,consumer,operation_id,payload_hash) VALUES ('$inbox','$tenant','$event','test','$operation','${"a".repeat(64)}')")
        reject("23514", "DELETE FROM inventory_inbox WHERE id='$inbox'")
        reject("23505", "INSERT INTO inventory_inbox(id,tenant_id,event_id,consumer,operation_id,payload_hash) VALUES ('${UUID.randomUUID()}','$tenant','$event','test','$operation','${"a".repeat(64)}')")
    }

    @Test
    fun `materialless plan and submitted revisions cannot be silently rewritten`() = fixture(false) {
        val plan = UUID.randomUUID()
        sql("INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id,reason) VALUES ('$plan','$tenant','${UUID.randomUUID()}',1,0,'NONE','$actor','No material required')")
        reject("23514", "INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit) VALUES ('${UUID.randomUUID()}','$tenant','$plan',1,'$sku',1,'MM')")
        sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=now(),revision=1 WHERE id='$plan'")
        materialPlanBindingSql(plan).forEach(::sql)
        reject("23514", "UPDATE inventory_material_plan SET reason='Rewritten',revision=2 WHERE id='$plan'")
    }

    @Test
    fun `app cannot manufacture legacy staging or reserved identity candidates`() = fixture(false) {
        reject("42501", "INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state) VALUES ('${UUID.randomUUID()}','$tenant','SERIAL','RESERVED','LEGACY_RESERVED')")
        reject("42501", "INSERT INTO inventory_identity_candidate(id,tenant_id,identity_type,source_table,source_id,raw_value) VALUES ('${UUID.randomUUID()}','$tenant','SERIAL','onu','${UUID.randomUUID()}','bad')")
        reject("42501", "INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,warehouse_admission) VALUES ('${UUID.randomUUID()}','$tenant','$sku','staged','MM',1,now(),'LEGACY_UNRESOLVED')")
    }

    @Test
    fun `reservation preserves piece dimension and picked quantities cannot expire`() = fixture {
        val reservation = UUID.randomUUID()
        val insert = """
            INSERT INTO inventory_reservation(id,tenant_id,document_line_id,sku_id,stock_identity_id,lot_id,base_unit,location_id,custodian_id,custodian_kind,condition,legal_owner,reserved_unpicked_base,reserved_picked_base,submitted_at,expires_at)
            VALUES ('$reservation','$tenant','$line','$sku','$segment','$lot','MM','$location','$actor','TECHNICIAN','SERVICEABLE','ISP',80000,2500,now(),now()+interval '1 day')
        """.trimIndent()
        sql(insert)
        reject("23505", insert.replace("'$reservation'", "'${UUID.randomUUID()}'"))
        reject("23514", "UPDATE inventory_reservation SET state='EXPIRED',reserved_unpicked_base=0,reserved_picked_base=0,revision=1 WHERE id='$reservation'")
        reject("23514", "UPDATE inventory_reservation SET reserved_unpicked_base=9223372036854775807,revision=1 WHERE id='$reservation'")
        assertThat(scalar("SELECT reserved_unpicked_base+reserved_picked_base FROM inventory_reservation WHERE id='$reservation'")).isEqualTo("82500")
    }

    @Test
    fun `inspection rejects over receipt quantities and unknown partial costs`() = fixture {
        val operation = operation()
        reject("23514", "INSERT INTO inventory_inspection(id,tenant_id,document_line_id,inspector_id,accepted_base,rejected_base,base_unit,disposition,evidence_reference,operation_id) VALUES ('${UUID.randomUUID()}','$tenant','$line','$actor',82501,0,'MM','ACCEPTED','evidence','$operation')")
        val inspection = UUID.randomUUID()
        sql("INSERT INTO inventory_inspection(id,tenant_id,document_line_id,inspector_id,accepted_base,rejected_base,base_unit,disposition,evidence_reference,operation_id) VALUES ('$inspection','$tenant','$line','$actor',82500,0,'MM','ACCEPTED','evidence','$operation')")
        reject("23514", "DELETE FROM inventory_inspection WHERE id='$inspection'")
        reject("23514", "INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id,cost_total_minor,cost_basis_quantity_base) VALUES ('${UUID.randomUUID()}','$tenant','$sku','bad-cost','MM',1,now(),'$line',1,1)")
    }

    @Test
    fun `admitted serial has one physical position and its claim survives retirement`() = fixture(false) {
        sql("UPDATE inventory_sku SET tracking='SERIAL',base_unit='EA',revision=1 WHERE id='$sku'")
        sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','serial','RECEIPT','$actor',0,0)")
        sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'$sku','EA','SERIAL',1,'$location','$actor','TECHNICIAN','QUARANTINE','ISP')")
        val claim = UUID.randomUUID()
        sql("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES ('$claim','$tenant','SERIAL','SERIAL-1','ADMITTED','$segment')")
        sql("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,warehouse_sku_id,serial_number,canonical_serial,status,location_id,custody_owner_id,custody_owner_kind,quantity_base,base_unit,condition,legal_owner,origin_document_line_id) VALUES ('$segment','$tenant','$sku','$sku',' Serial-1 ','SERIAL-1','QUARANTINE','$location','$actor','TECHNICIAN',1,'EA','QUARANTINE','ISP','$line')")
        sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,asset_id,kind,base_unit,quantity_base) VALUES ('$segment','$tenant','$sku','$segment','SERIAL','EA',1)")
        sql("UPDATE inventory_document_line SET stock_identity_id='$segment',revision=1 WHERE id='$line'")
        sql("UPDATE inventory_document SET state='RECEIVED_IN_INSPECTION',revision=1 WHERE id='$document'")
        sql("SET CONSTRAINTS ALL IMMEDIATE")
        val position = balance(quantity="1").replace("'$lot'", "NULL").replace("'MM'", "'EA'")
        sql(position)
        reject("23505", balance(quantity="1").replace("'$lot'", "NULL").replace("'MM'", "'EA'").replace("'$actor'", "'${UUID.randomUUID()}'"))
        reject("23514", "UPDATE inventory_serialized_asset SET serial_number='Other',revision=1 WHERE id='$segment'")
        reject("23514", "UPDATE inventory_identity_claim SET state='RETIRED',revision=1 WHERE id='$claim'")
        sql("SET CONSTRAINTS ALL DEFERRED")
        sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=1 WHERE stock_identity_id='$segment'")
        sql("UPDATE inventory_segment SET state='RETIRED',revision=1 WHERE id='$segment'")
        sql("UPDATE inventory_identity_claim SET state='RETIRED',revision=1 WHERE id='$claim'")
        sql("SET CONSTRAINTS ALL IMMEDIATE")
        reject("23514", "UPDATE inventory_balance_projection SET quantity_base=1,revision=2 WHERE stock_identity_id='$segment'")
        reject("23505", "INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES ('${UUID.randomUUID()}','$tenant','SERIAL','SERIAL-1','ADMITTED','$segment')")
    }
}
