package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class WarehouseTenantControlErasureUpgradeIT {
    private val controls = listOf("inventory_tenant_cutover", "iam_authorization_epoch")

    @Test
    fun `upgrade preserves control epochs and permits only scoped empty tenant cascades`() {
        WarehouseSchemaDatabase("178.8").use { database ->
            val empty = seed(database)
            val other = seed(database)
            val before = snapshot(database, empty)
            val untouched = snapshot(database, other)
            for (table in controls) reject(database, empty, "DELETE FROM $table WHERE tenant_id='$empty'", "23514")
            reject(database, empty, "DELETE FROM tenant WHERE id='$empty'", "23503")

            assertThat(database.migrate().migrations.map { it.version }).contains("178.9")
            assertThat(snapshot(database, empty)).isEqualTo(before)
            for (table in controls) reject(database, empty, "DELETE FROM $table WHERE tenant_id='$empty'", "23514")
            for (scope in listOf("", other.toString(), UUID.randomUUID().toString())) {
                reject(database, empty, "DELETE FROM tenant WHERE id='$empty'", "23514", scope)
                assertThat(snapshot(database, empty)).isEqualTo(before)
            }

            val populated = database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                val fixture = WarehouseSchemaFixture(connection)
                fixture.masters()
                insertControls(connection, fixture.tenant)
                connection.commit()
                fixture.tenant
            }
            val historyControls = snapshot(database, populated)
            reject(database, populated, "DELETE FROM tenant WHERE id='$populated'", "23503")
            assertThat(snapshot(database, populated)).isEqualTo(historyControls)
            database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                val fixture = WarehouseSchemaFixture(connection)
                fixture.sql("SET LOCAL app.tenant_id='$populated'")
                assertThat(fixture.scalar("SELECT count(*) FROM inventory_sku WHERE tenant_id='$populated'")).isEqualTo("1")
                connection.rollback()
            }

            database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                val fixture = WarehouseSchemaFixture(connection)
                fixture.sql("SET LOCAL app.tenant_id='$empty'")
                fixture.sql("DELETE FROM tenant WHERE id='$empty'")
                connection.commit()
            }
            database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                val fixture = WarehouseSchemaFixture(connection)
                fixture.sql("SET LOCAL app.tenant_id='$empty'")
                assertThat(fixture.scalar("SELECT count(*) FROM tenant WHERE id='$empty'")).isEqualTo("0")
                for (table in controls) {
                    assertThat(fixture.scalar("SELECT count(*) FROM $table WHERE tenant_id='$empty'")).isEqualTo("0")
                }
                connection.rollback()
            }
            assertThat(snapshot(database, other)).isEqualTo(untouched)
            assertThat(database.migrate().migrationsExecuted).isZero()
        }
    }

    private fun seed(database: WarehouseSchemaDatabase): UUID = database.dataSource.connection.use { connection ->
        connection.autoCommit = false
        val tenant = UUID.randomUUID()
        val fixture = WarehouseSchemaFixture(connection)
        fixture.sql("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','control-$tenant','Empty control fixture')")
        fixture.sql("SET LOCAL app.tenant_id='$tenant'")
        insertControls(connection, tenant)
        connection.commit()
        tenant
    }

    private fun insertControls(connection: Connection, tenant: UUID) {
        val fixture = WarehouseSchemaFixture(connection)
        for (table in controls) fixture.sql("INSERT INTO $table(id,tenant_id) VALUES (gen_random_uuid(),'$tenant')")
        fixture.sql("UPDATE iam_authorization_epoch SET epoch=1,revision=1 WHERE tenant_id='$tenant'")
    }

    private fun snapshot(database: WarehouseSchemaDatabase, tenant: UUID): List<String> =
        database.dataSource.connection.use { connection ->
            connection.autoCommit = false
            val fixture = WarehouseSchemaFixture(connection)
            fixture.sql("SET LOCAL app.tenant_id='$tenant'")
            controls.map { fixture.scalar("SELECT to_jsonb(control)::text FROM $it control WHERE tenant_id='$tenant'") }
                .also { connection.rollback() }
        }

    private fun reject(database: WarehouseSchemaDatabase, tenant: UUID, command: String, state: String,
                       scope: String = tenant.toString()) {
        database.dataSource.connection.use { connection ->
            connection.autoCommit = false
            val fixture = WarehouseSchemaFixture(connection)
            fixture.sql("SET LOCAL app.tenant_id='$scope'")
            val failure = assertThrows<SQLException> { fixture.sql(command); connection.commit() }
            assertThat(failure.sqlState).isEqualTo(state)
            connection.rollback()
        }
    }
}
