package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseMasterITSiteSchema {
    @Test fun `AV7-02 SQL tenant FK site area and parent site constraints survive bypass of owner service`() {
        WarehouseSchemaDatabase().use { database -> database.dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                with(WarehouseSchemaFixture(connection)) {
                    masters()
                    val first = UUID.randomUUID(); val second = UUID.randomUUID(); val area = UUID.randomUUID()
                    sql("INSERT INTO site(id,tenant_id,code,name,location) VALUES ('$first','$tenant','A','A',ST_SetSRID(ST_MakePoint(1,1),4326)),('$second','$tenant','B','B',ST_SetSRID(ST_MakePoint(1,1),4326))")
                    sql("UPDATE inventory_location SET site_id='$first',revision=1 WHERE id='$location'")
                    reject("23503", "DELETE FROM site WHERE id='$first'")
                    sql("INSERT INTO area(id,tenant_id,code,name) VALUES ('$area','$tenant','AREA','Area')")
                    reject("23514", "UPDATE site SET area_id='$area' WHERE id='$first'")
                    reject("23514", "INSERT INTO inventory_location(id,tenant_id,code,kind,parent_location_id,site_id) VALUES ('${UUID.randomUUID()}','$tenant','child','BIN','$location','$second')")
                    reject("23514", "INSERT INTO inventory_location(id,tenant_id,code,kind,site_id,area_id) VALUES ('${UUID.randomUUID()}','$tenant','wrong-area','WAREHOUSE','$first','$area')")
                    val foreign = UUID.randomUUID(); val foreignSite = UUID.randomUUID()
                    sql("INSERT INTO tenant(id,slug,name) VALUES ('$foreign','foreign-$foreign','Foreign')")
                    sql("SET LOCAL app.tenant_id='$foreign'")
                    sql("INSERT INTO site(id,tenant_id,code,name,location) VALUES ('$foreignSite','$foreign','FOREIGN','Foreign',ST_SetSRID(ST_MakePoint(1,1),4326))")
                    sql("SET LOCAL app.tenant_id='$tenant'")
                    reject("23503", "INSERT INTO inventory_location(id,tenant_id,code,kind,site_id) VALUES ('${UUID.randomUUID()}','$tenant','foreign','WAREHOUSE','$foreignSite')")
                    sql("INSERT INTO inventory_location(id,tenant_id,code,kind,parent_location_id,site_id) VALUES ('${UUID.randomUUID()}','$tenant','child','BIN','$location','$first')")
                    reject("23514", "UPDATE inventory_location SET site_id='$second',revision=revision+1 WHERE id='$location'")
                    assertThat(scalar("SELECT count(*) FROM pg_constraint WHERE conrelid='inventory_location'::regclass AND conname='warehouse_location_site_fk' AND contype='f'")).isEqualTo("1")
                }
            } finally { connection.rollback() }
        } }
    }
}
