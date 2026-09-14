package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class CustomerAssetAuthorizationITUpgrade {
    @Test
    fun `V175_54 bad authorization remains readable but cannot validate or consume after upgrade`() {
        WarehouseSchemaDatabase("175.54").use { database ->
            val tenant = UUID.randomUUID()
            val asset = UUID.randomUUID()
            val customer = UUID.randomUUID()
            val workOrder = UUID.randomUUID()
            val actor = UUID.randomUUID()
            val location = UUID.randomUUID()
            val permit = UUID.randomUUID()
            database.ownerFixture { owner ->
                owner.createStatement().use { statement ->
                    statement.execute("SET app.tenant_id='$tenant'")
                    statement.execute("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','auth-$tenant','Authorization upgrade')")
                    statement.execute("INSERT INTO app_user(id,tenant_id,email,name,password_hash) VALUES ('$actor','$tenant','qa@example.invalid','QA','unusable-fixture')")
                    statement.execute("INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$customer','$tenant','QA','QA','QA')")
                    statement.execute("INSERT INTO work_order(id,tenant_id,code,type,title,created_by) VALUES ('$workOrder','$tenant','QA','PREVENTIVE','QA','$actor')")
                    statement.execute("INSERT INTO inventory_location(id,tenant_id,code,kind) VALUES ('$location','$tenant','QA','WAREHOUSE')")
                    statement.execute("""INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,status,location_id,custody_owner_id,custody_owner_kind,warehouse_admission)
                        VALUES ('$asset','$tenant','${UUID.randomUUID()}','LEGACY','RETURNED','$location','$location','WAREHOUSE','LEGACY_UNRESOLVED')""")
                    statement.execute("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state) VALUES ('${UUID.randomUUID()}','$tenant','SERIAL','LEGACY','CONFLICT')")
                }
            }
            val before = database.dataSource.connection.use { app ->
                app.autoCommit = false
                app.createStatement().use { statement ->
                    statement.execute("SET LOCAL app.tenant_id='$tenant'")
                    statement.execute("""INSERT INTO inventory_deployment_authorization(id,tenant_id,asset_id,work_order_id,customer_id,actor_id,purpose,ownership_mode,operation_id,
                        expected_asset_revision,expected_work_order_revision,expected_plan_revision,authority_epoch,cutover_epoch)
                        VALUES ('$permit','$tenant','$asset','$workOrder','$customer','$actor','REMOVE','LOAN','${UUID.randomUUID()}',0,0,0,0,0)""")
                }
                app.commit()
                app.createStatement().use { it.execute("SET app.tenant_id='$tenant'") }
                scalar(app, "SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$permit'")
            }
            assertThat(database.migrate().migrationsExecuted).isEqualTo(4)
            assertThat(database.migrate().migrationsExecuted).isZero()
            database.dataSource.connection.use { app ->
                app.createStatement().use { it.execute("SET app.tenant_id='$tenant'") }
                assertThat(scalar(app, "SELECT snapshot::text FROM inventory_deployment_authorization_history WHERE authorization_id='$permit'")).isEqualTo(before)
                for (zone in listOf("UTC", "America/New_York", "Asia/Kathmandu", "Australia/Lord_Howe")) {
                    app.createStatement().use { it.execute("SET TIME ZONE '$zone'") }
                    for (sql in listOf("SELECT warehouse_read_deployment_authorization('$tenant','$permit')",
                        "UPDATE inventory_deployment_authorization SET consumed=true,consumed_at=now(),revision=1 WHERE id='$permit'")) {
                        val failure = assertThrows<SQLException> { app.createStatement().use { it.execute(sql) } }
                        assertThat(failure.sqlState).isEqualTo("23514")
                        assertThat(failure.message).contains("authorization requires VERIFIED physical asset")
                    }
                }
                assertThat(scalar(app, "SELECT consumed::text FROM inventory_deployment_authorization WHERE id='$permit'")).isEqualTo("false")
                assertThat(scalar(app, "SELECT count(*) FROM inventory_deployment_authorization_history WHERE authorization_id='$permit'")).isEqualTo("1")
            }
        }
    }

    private fun scalar(connection: Connection, sql: String): String = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> check(rows.next()); rows.getString(1) }
    }
}
