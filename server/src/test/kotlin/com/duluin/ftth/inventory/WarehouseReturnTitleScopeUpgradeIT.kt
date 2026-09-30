package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.UUID

class WarehouseReturnTitleScopeUpgradeIT {
    @Test
    fun `return title guard rejects changed scope after other validators already passed`() {
        WarehouseSchemaDatabase("178.7").use { database ->
            // Reproduce the old conditional guard: an RLS-filtered lookup skipped
            // the entry assertion for an unrelated document with a cleared scope.
            probe(database, "CLEARED", rejected = false)
            assertThat(database.migrate().migrations.map { it.version }).contains("178.8")
            for (scope in listOf("CLEARED", "FOREIGN", "STALE")) probe(database, scope, rejected = true)
            for (scope in listOf("CORRECT", "RESTORED")) probe(database, scope, rejected = false)
            assertThat(database.migrate().migrationsExecuted).isZero()
        }
    }

    private fun probe(database: WarehouseSchemaDatabase, scope: String, rejected: Boolean) {
        WarehouseTimingFixture(database, false).use { fixture ->
            val document = UUID.randomUUID()
            val tenant = fixture.stock.tenant
            val foreign = UUID.randomUUID()
            fixture.stock.sql("INSERT INTO tenant(id,slug,name) VALUES ('$foreign','scope-$foreign','Other tenant')")
            fixture.stock.sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) " +
                "VALUES ('$document','$tenant','scope-$document','RECEIPT','${fixture.stock.actor}',0,0)")
            fixture.holdOnly("warehouse_return_title_final")
            when (scope) {
                "CLEARED" -> fixture.stock.sql("SET LOCAL app.tenant_id=''")
                "FOREIGN" -> fixture.stock.sql("SET LOCAL app.tenant_id='$foreign'")
                "STALE" -> fixture.stock.sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                "RESTORED" -> {
                    fixture.stock.sql("SET LOCAL app.tenant_id='$foreign'")
                    fixture.stock.sql("SET LOCAL app.tenant_id='$tenant'")
                }
                "CORRECT" -> Unit
                else -> error("Unknown scope")
            }
            if (rejected) {
                val failure = assertThrows<SQLException> { fixture.connection.commit() }
                assertThat(failure.sqlState).isEqualTo("23514")
                assertThat(failure.message).contains("row tenant scope")
                fixture.connection.rollback()
            } else fixture.connection.commit()
            database.dataSource.connection.use { fresh ->
                fresh.autoCommit = false
                val read = WarehouseSchemaFixture(fresh)
                read.sql("SET LOCAL app.tenant_id='$tenant'")
                assertThat(read.scalar("SELECT count(*) FROM inventory_document WHERE id='$document'"))
                    .isEqualTo(if (rejected) "0" else "1")
                fresh.rollback()
            }
        }
    }
}
