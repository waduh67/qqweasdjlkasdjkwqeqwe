package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseReplenishmentITUpgrade {
    @Test fun `forward upgrade preserves existing rule and request without inventing acceptance`() {
        WarehouseSchemaDatabase("175.112").use { database ->
            val tenant = UUID.randomUUID()
            val sku = UUID.randomUUID()
            val location = UUID.randomUUID()
            val rule = UUID.randomUUID()
            database.ownerFixture { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("SET app.tenant_id='$tenant'")
                    sql.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','replenish-$tenant','Upgrade')")
                    sql.execute("INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('$sku','$tenant','EA','EA','BULK','EA')")
                    sql.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$location','$tenant','WH','WAREHOUSE')")
                    sql.execute("INSERT INTO inventory_replenishment_rule(id,tenant_id,sku_id,location_id,minimum_base,maximum_base,base_unit) VALUES ('$rule','$tenant','$sku','$location',5,20,'EA')")
                    sql.execute("INSERT INTO inventory_replenishment_request(id,tenant_id,rule_id,rule_revision,business_key,quantity_base,state) VALUES (gen_random_uuid(),'$tenant','$rule',0,'preserved',15,'PENDING')")
                }
            }
            assertThat(database.migrate().migrationsExecuted).isEqualTo(2)
            assertThat(database.migrate().migrationsExecuted).isZero()
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("SET app.tenant_id='$tenant'")
                    sql.executeQuery("SELECT quantity_base,accepted_at,rule_snapshot FROM inventory_replenishment_request WHERE business_key='preserved'").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getLong(1)).isEqualTo(15)
                        assertThat(it.getObject(2)).isNull()
                        assertThat(it.getObject(3)).isNull()
                    }
                    sql.executeQuery("SELECT minimum_base,maximum_base,package_multiple_base,lead_time_days FROM inventory_replenishment_rule").use {
                        assertThat(it.next()).isTrue()
                        assertThat(it.getLong(1)).isEqualTo(5)
                        assertThat(it.getLong(2)).isEqualTo(20)
                        assertThat(it.getLong(3)).isEqualTo(1)
                        assertThat(it.getInt(4)).isZero()
                    }
                }
            }
        }
    }
}
