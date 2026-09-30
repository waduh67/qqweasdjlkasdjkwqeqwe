package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.WarehouseLotCapacityFixture.Companion.sql
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class WarehouseSchemaITDeferredScope {
    @Test
    fun `V174_3 upgrade preserves old hashes and validates scope before no op rerun`() {
        WarehouseSchemaDatabase("174.3").use { database ->
            val stock=WarehouseLotCapacityFixture(database)
            stock.create(100)
            assertThat(database.migrate("174.4").migrationsExecuted).isEqualTo(1)
            val failure=runCatching {
                stock.open().use { connection ->
                    stock.addPiece(connection,1)
                    connection.sql("SET LOCAL app.tenant_id=''")
                    connection.commit()
                }
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SQLException::class.java)
            assertThat((failure as SQLException).sqlState).isEqualTo("23514")
            assertThat(stock.totals()).containsExactly(100L,100L,100L)
            assertThat(database.migrate("174.4").migrationsExecuted).isZero()
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["CORRECT","CLEARED","FOREIGN","RESTORED","STALE"])
    fun `deferred capacity cannot be bypassed by tenant context at commit`(mode: String) {
        WarehouseSchemaDatabase().use { database ->
            val stock=WarehouseLotCapacityFixture(database)
            stock.create(100)
            val foreign=foreignTenant(database)
            val failure=runCatching {
                stock.open().use { connection ->
                    stock.addPiece(connection,1)
                    changeScope(connection,mode,stock.tenant,foreign)
                    connection.commit()
                }
            }.exceptionOrNull()
            val totals=stock.totals()
            println("GUC $mode commit=${(failure as? SQLException)?.sqlState ?: "00000"} fresh=$totals")
            assertThat(failure).isInstanceOf(SQLException::class.java)
            assertThat((failure as SQLException).sqlState).isEqualTo("23514")
            assertThat(failure.message).contains(if (mode in setOf("CORRECT","RESTORED")) "exceeds received lot quantity" else "row tenant scope")
            assertThat(totals).containsExactly(100L,100L,100L)
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["CORRECT","RESTORED"])
    fun `correct or restored context permits legitimate exact capacity commit`(mode: String) {
        WarehouseSchemaDatabase().use { database ->
            val stock=WarehouseLotCapacityFixture(database)
            stock.create(40)
            val foreign=foreignTenant(database)
            stock.open().use { connection ->
                stock.addPiece(connection,60)
                changeScope(connection,mode,stock.tenant,foreign)
                connection.commit()
            }
            assertThat(stock.totals()).containsExactly(100L,100L,100L)
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["CLEARED","FOREIGN"])
    fun `balance only provenance validation cannot disappear behind RLS`(mode: String) {
        WarehouseSchemaDatabase().use { database ->
            val stock=WarehouseLotCapacityFixture(database)
            stock.create(40)
            val foreign=foreignTenant(database)
            val staged=UUID.randomUUID()
            database.ownerFixture { connection ->
                connection.autoCommit=false
                connection.sql("SET LOCAL app.tenant_id='${stock.tenant}'")
                connection.sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base,warehouse_admission) VALUES ('$staged','${stock.tenant}','${stock.sku}','${stock.lot}','REEL','MM',1,'LEGACY_UNRESOLVED')")
                connection.commit()
            }
            val failure=runCatching {
                stock.open().use { connection ->
                    connection.sql("INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,rebuilt_at,quantity_base,base_unit,stock_identity_id,lot_id,condition,legal_owner) VALUES ('${UUID.randomUUID()}','${stock.tenant}','$staged','${stock.sku}','${stock.location}','${stock.location}','WAREHOUSE','AVAILABLE',now(),1,'MM','$staged','${stock.lot}','SERVICEABLE','ISP')")
                    changeScope(connection,mode,stock.tenant,foreign)
                    connection.commit()
                }
            }.exceptionOrNull()
            val totals=stock.totals()
            println("provenance GUC $mode commit=${(failure as? SQLException)?.sqlState ?: "00000"} fresh=$totals")
            assertThat(failure).isInstanceOf(SQLException::class.java)
            assertThat((failure as SQLException).sqlState).isEqualTo("23514")
            assertThat(failure.message).contains("row tenant scope")
            assertThat(totals).containsExactly(40L,40L,40L)
        }
    }

    private fun foreignTenant(database: WarehouseSchemaDatabase): UUID {
        val tenant=UUID.randomUUID()
        database.dataSource.connection.use { it.sql("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','foreign-$tenant','Foreign')") }
        return tenant
    }

    private fun changeScope(connection: Connection, mode: String, correct: UUID, foreign: UUID) {
        when(mode) {
            "CORRECT" -> Unit
            "CLEARED" -> connection.sql("SET LOCAL app.tenant_id=''")
            "FOREIGN" -> connection.sql("SET LOCAL app.tenant_id='$foreign'")
            "STALE" -> connection.sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
            "RESTORED" -> {
                connection.sql("SET LOCAL app.tenant_id='$foreign'")
                connection.sql("SET LOCAL app.tenant_id='$correct'")
            }
            else -> error("Unknown test mode")
        }
    }
}
