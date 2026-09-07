package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID

class WarehouseSchemaITProvenance {
    @Test
    fun `AV01 upgraded conflict asset cannot mint verified available serial stock`() {
        WarehouseSchemaDatabase("172").use { database ->
            val tenant = UUID.randomUUID()
            val location = UUID.randomUUID()
            val asset = UUID.randomUUID()
            database.ownerFixture { connection -> connection.createStatement().use {
                it.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','av01-$tenant','AV01')")
                it.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$location','$tenant','legacy','WAREHOUSE')")
                for ((id, raw) in listOf(asset to " Serial-A ", UUID.randomUUID() to "serial-a")) {
                    it.execute("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,status,location_id,custody_owner_id,custody_owner_kind) VALUES ('$id','$tenant','${UUID.randomUUID()}','$raw','AVAILABLE','$location','$location','WAREHOUSE')")
                }
            } }
            database.migrate()
            val failure = runCatching {
                database.dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    val probe = WarehouseSchemaFixture(connection)
                    val sku = UUID.randomUUID()
                    probe.sql("SET LOCAL app.tenant_id='$tenant'")
                    assertThat(probe.scalar("SELECT state FROM inventory_identity_claim WHERE canonical_value='SERIAL-A'")).isEqualTo("CONFLICT")
                    probe.sql("INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('$sku','$tenant','serial','Serial','SERIAL','EA')")
                    probe.sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,asset_id,kind,base_unit,quantity_base) VALUES ('$asset','$tenant','$sku','$asset','SERIAL','EA',1)")
                    probe.sql("INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,rebuilt_at,quantity_base,base_unit,stock_identity_id,condition,legal_owner) VALUES ('${UUID.randomUUID()}','$tenant','$asset','$sku','$location','$location','WAREHOUSE','AVAILABLE',now(),1,'EA','$asset','SERVICEABLE','ISP')")
                    connection.commit()
                }
            }.exceptionOrNull()
            database.dataSource.connection.use { connection ->
                val probe = WarehouseSchemaFixture(connection)
                probe.sql("SET app.tenant_id='$tenant'")
                val available = probe.scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED' AND condition='SERVICEABLE' AND legal_owner='ISP'")
                println("AV01 commitState=${(failure as? SQLException)?.sqlState ?: "00000"} freshAvailable=$available")
                assertThat(failure).isInstanceOf(SQLException::class.java)
                assertThat(available).isEqualTo("0")
            }
        }
    }

    @Test
    fun `AV02 opening status alone cannot authorize verified origin`() = fixture(false) {
        val failure = assertThrows<SQLException> {
            sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','opening','OPENING_BALANCE','$actor',0,0)")
            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'$sku','MM','LOT',82500,'$location','$actor','TECHNICIAN','SERVICEABLE','ISP')")
            sql("INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id) VALUES ('$lot','$tenant','$sku','opening','MM',82500,now(),'$line')")
            sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base) VALUES ('$segment','$tenant','$sku','$lot','REEL','MM',82500)")
            sql("UPDATE inventory_document_line SET stock_identity_id='$segment',lot_id='$lot',revision=1 WHERE id='$line'")
            for ((index,state) in listOf("SUBMITTED","APPROVED","POSTED").withIndex()) {
                sql("UPDATE inventory_document SET state='$state',revision=${index+1} WHERE id='$document'")
            }
            connection.commit()
        }
        assertThat(failure.sqlState).isEqualTo("42501")
    }

    @Test
    fun `AV03 equal quantity child cannot change parent lot provenance`() = fixture {
        val otherLot = UUID.randomUUID()
        sql("INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id) VALUES ('$otherLot','$tenant','$sku','other','MM',82500,now(),'$line')")
        sql("UPDATE inventory_segment SET state='SPLIT',revision=1 WHERE id='$segment'")
        sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base) VALUES ('${UUID.randomUUID()}','$tenant','$sku','$otherLot','$segment','CUT','MM',82500)")
        assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
    }

    @Test
    fun `AV03 split parent cannot retain positive verified balance`() = fixture {
        sql(balance())
        split()
        assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
    }

    @Test
    fun `AV03 retired parent cannot retain positive verified balance`() = fixture {
        sql(balance())
        sql("UPDATE inventory_segment SET state='RETIRED',revision=1 WHERE id='$segment'")
        assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
    }

    @ParameterizedTest
    @ValueSource(strings=["SEGMENT","BALANCE","RESERVATION"])
    fun `unresolved lot cannot authorize a verified chain`(target: String) {
        WarehouseSchemaDatabase().use { database -> database.dataSource.connection.use { connection ->
            connection.autoCommit=false
            val probe=WarehouseSchemaFixture(connection)
            probe.masters()
            connection.commit()
            database.ownerFixture { owner ->
                owner.autoCommit=false
                owner.createStatement().use {
                    it.execute("SET LOCAL app.tenant_id='${probe.tenant}'")
                    it.execute("INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,warehouse_admission) VALUES ('${probe.lot}','${probe.tenant}','${probe.sku}','legacy','MM',82500,now(),'LEGACY_UNRESOLVED')")
                    it.execute("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base,warehouse_admission) VALUES ('${probe.segment}','${probe.tenant}','${probe.sku}','${probe.lot}','REEL','MM',82500,'LEGACY_UNRESOLVED')")
                }
                owner.commit()
            }
            probe.apply {
                sql("SET LOCAL app.tenant_id='$tenant'")
                val failure=assertThrows<SQLException> {
                    when(target) {
                        "SEGMENT" -> sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base) VALUES ('${UUID.randomUUID()}','$tenant','$sku','$lot','CUT','MM',1)")
                        "BALANCE" -> sql(balance())
                        "RESERVATION" -> {
                            sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','demand','DEMAND','$actor',0,0)")
                            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base) VALUES ('$line','$tenant','$document',1,0,'$sku','MM','LOT',82500)")
                            sql(reservation(UUID.randomUUID(),segment,1,0))
                        }
                    }
                    connection.commit()
                }
                assertThat(failure.sqlState).isEqualTo("23514")
            }
            connection.rollback()
        } }
    }

    @ParameterizedTest
    @ValueSource(strings=["SPLIT_UNPICKED","SPLIT_PICKED","RETIRED_UNPICKED","RETIRED_PICKED"])
    fun `terminal parent cannot retain open encumbrance`(scenario: String) = fixture {
        val picked=scenario.endsWith("_PICKED")
        sql(reservation(UUID.randomUUID(),segment,if(picked) 0 else 1,if(picked) 1 else 0))
        if(scenario.startsWith("SPLIT")) split() else sql("UPDATE inventory_segment SET state='RETIRED',revision=1 WHERE id='$segment'")
        assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
    }

    @Test
    fun `legitimate receipt and same lot split move all stock and reservations atomically`() = fixture {
        val originalReservation=UUID.randomUUID()
        sql(balance())
        sql(reservation(originalReservation,segment,80000,2500))
        sql("UPDATE inventory_segment SET state='SPLIT',revision=1 WHERE id='$segment'")
        for(quantity in listOf(80000L,2500L)) {
            val child=UUID.randomUUID()
            sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base) VALUES ('$child','$tenant','$sku','$lot','$segment','CUT','MM',$quantity)")
            sql(balance(quantity=quantity.toString()).replace("'$segment'","'$child'"))
            sql(reservation(UUID.randomUUID(),child,quantity,0))
        }
        sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=1 WHERE stock_identity_id='$segment'")
        sql("UPDATE inventory_reservation SET state='RELEASED',reserved_unpicked_base=0,reserved_picked_base=0,revision=1 WHERE id='$originalReservation'")
        connection.commit()
        sql("SET LOCAL app.tenant_id='$tenant'")
        assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("82500")
        assertThat(scalar("SELECT sum(reserved_unpicked_base+reserved_picked_base) FROM inventory_reservation WHERE state='OPEN'")).isEqualTo("82500")
        assertThat(scalar("SELECT quantity_base FROM inventory_balance_projection WHERE stock_identity_id='$segment'")).isEqualTo("0")
    }

    private fun WarehouseSchemaFixture.reservation(id: UUID, identity: UUID, unpicked: Long, picked: Long): String = """
        INSERT INTO inventory_reservation(id,tenant_id,document_line_id,sku_id,stock_identity_id,lot_id,base_unit,location_id,custodian_id,custodian_kind,condition,legal_owner,reserved_unpicked_base,reserved_picked_base,submitted_at,expires_at)
        VALUES ('$id','$tenant','$line','$sku','$identity','$lot','MM','$location','$actor','TECHNICIAN','SERVICEABLE','ISP',$unpicked,$picked,now(),now()+interval '1 day')
    """.trimIndent()

    private fun WarehouseSchemaFixture.split() {
        sql("UPDATE inventory_segment SET state='SPLIT',revision=1 WHERE id='$segment'")
        sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base) VALUES ('${UUID.randomUUID()}','$tenant','$sku','$lot','$segment','CUT','MM',82500)")
    }

    private fun fixture(receipt: Boolean = true, action: WarehouseSchemaFixture.() -> Unit) {
        WarehouseSchemaDatabase().use { database -> database.dataSource.connection.use { connection ->
            connection.autoCommit=false
            try { WarehouseSchemaFixture(connection).apply { if (receipt) receipt() else masters(); action() } }
            finally { connection.rollback() }
        } }
    }
}
