package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseQueryITStaged : WarehouseReceiptHttpFixture() {
    @Test fun `unverified raw quantities and legacy identities are explicit without becoming available`() {
        val setup = setupReceipt()
        val tenant = fixture(setup.token).tenant
        val asset = UUID.randomUUID()
        val balance = UUID.randomUUID()
        context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource.connection.use { connection ->
            connection.autoCommit=false
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL app.tenant_id='$tenant'")
                statement.execute("""INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,status,location_id,custody_owner_id,custody_owner_kind,warehouse_admission)
                    VALUES ('$asset','$tenant','${setup.onu}',' old raw serial ','AVAILABLE','${setup.inspection}','${setup.inspection}','WAREHOUSE','LEGACY_UNRESOLVED')""")
                statement.execute("""INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,quantity,rebuilt_at,warehouse_admission)
                    VALUES ('$balance','$tenant','${UUID.randomUUID()}','${setup.cable}','${setup.inspection}','${setup.inspection}','WAREHOUSE','AVAILABLE',37,now(),'LEGACY_UNRESOLVED')""")
            }
            connection.commit()
        }
        val before = fixture(setup.token).transaction { counts() }
        val staged = request("GET","/api/v1/warehouse/stock/unknown",setup.token)
        assertThat(staged.status).withFailMessage(staged.contentAsString).isEqualTo(200)
        val rows = mapper.readTree(staged.contentAsString).path("items")
        assertThat(rows.size()).isEqualTo(2)
        assertThat(rows.all { !it.path("available").asBoolean() && it.path("baseUnit").isNull }).isTrue()
        assertThat(rows.single { it.path("id").asString()==balance.toString() }.path("rawQuantity").asString()).isEqualTo("37")
        assertThat(rows.single { it.path("id").asString()==asset.toString() }.path("serial").asString()).isEqualTo(" old raw serial ")
        assertThat(mapper.readTree(request("GET","/api/v1/warehouse/stock",setup.token).contentAsString).path("totalElements").asInt()).isZero()
        assertThat(request("GET","/api/v1/warehouse/stock/unknown",user(setup.token,setOf("inventory.item.view")).first).status).isEqualTo(403)
        assertThat(fixture(setup.token).transaction { counts() }).isEqualTo(before)
    }

    @Test fun `historical projection inconsistency is reported rather than rebuilt or hidden`() {
        val setup = setupReceipt()
        val receipt = draft(setup,"""{"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"INCONSISTENT"}""")
        transition(setup,receipt.path("id").asString(),"receive","""{"expectedRevision":0}""")
        val fixture = fixture(setup.token)
        val lot = fixture.transaction {
            sql("UPDATE inventory_balance_projection SET quantity_base=999,revision=revision+1 WHERE sku_id='${setup.cable}'")
            scalar("SELECT id FROM inventory_lot")
        }
        val detail = request("GET","/api/v1/warehouse/lots/$lot",setup.token)
        assertThat(detail.status).withFailMessage(detail.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(detail.contentAsString).path("conservation").path("consistent").asBoolean()).isFalse()
        fixture.transaction { assertThat(scalar("SELECT sum(quantity_base) FROM inventory_balance_projection")).isEqualTo("999") }
    }
}
