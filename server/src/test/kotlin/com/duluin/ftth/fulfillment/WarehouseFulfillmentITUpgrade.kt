package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseFulfillmentITUpgrade {
    @ParameterizedTest @ValueSource(strings = ["175.23", "175.24", "latest"])
    fun `forward settlement migration and restart preserve applied checksums`(starting: String) {
        WarehouseSchemaDatabase(starting).use { database ->
            fun checksums(): Map<String, Int> = database.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT version,checksum FROM flyway_schema_history WHERE version IN ('175.23','175.24')").use { rows ->
                        buildMap { while (rows.next()) put(rows.getString(1), rows.getInt(2)) }
                    }
                }
            }
            val before = checksums()

            val result = database.migrate()

            assertThat(result.migrationsExecuted).isEqualTo(when (starting) { "175.23" -> 11; "175.24" -> 10; else -> 0 })
            assertThat(checksums()).containsAllEntriesOf(before)
            assertThat(database.migrate().migrationsExecuted).isZero()
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("""SELECT count(*) FROM pg_class WHERE relnamespace=current_schema()::regnamespace
                        AND relname IN ('fulfillment_approval_snapshot','inventory_material_settlement') AND relrowsecurity AND relforcerowsecurity""").use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getInt(1)).isEqualTo(2)
                    }
                }
            }
        }
    }
}
