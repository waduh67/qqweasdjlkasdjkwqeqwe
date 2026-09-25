package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class WarehouseDatabaseIsolationIT {
    @Test fun `historical public-qualified migrations remain confined to their disposable database`() {
        val before = sharedFunctions()
        WarehouseSchemaDatabase("175.131").use { database ->
            assertThat(database.databaseName).startsWith("warehouse_fixture_")
            assertThat(database.schema).isEqualTo("public")
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_database(),current_user,rolsuper,rolbypassrls,rolcreatedb FROM pg_roles WHERE rolname=current_user").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString(1)).isEqualTo(database.databaseName)
                        assertThat(rows.getString(2)).isEqualTo("warehouse_app")
                        for (column in 3..5) assertThat(rows.getBoolean(column)).isFalse()
                    }
                }
            }
            // V175.132 contains explicit public.function definitions. This used to
            // overwrite the shared database when isolation only changed search_path.
            assertThat(database.migrate().migrationsExecuted).isPositive()
            assertThat(sharedFunctions()).isEqualTo(before)
            assertThat(database.migrate().migrationsExecuted).isZero()
        }
        assertThat(sharedFunctions()).isEqualTo(before)
    }

    private fun sharedFunctions(): String? = DriverManager.getConnection(
        requireNotNull(System.getenv("SPRING_DATASOURCE_URL")),
        requireNotNull(System.getenv("SPRING_FLYWAY_USER")),
        requireNotNull(System.getenv("SPRING_FLYWAY_PASSWORD")),
    ).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT md5(string_agg(pg_get_functiondef(p.oid), E'\\n' ORDER BY p.proname,p.oid))
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname='public' AND p.proname LIKE 'warehouse_%' AND p.prokind IN ('f','p')""").use {
                check(it.next()); it.getString(1)
            }
        }
    }
}
