package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.WarehouseLotCapacityFixture.Companion.sql
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID
import java.util.stream.Stream

class WarehouseSchemaITSelectiveTiming {
    companion object {
        private lateinit var database: WarehouseSchemaDatabase
        @JvmStatic @BeforeAll fun start() { database=WarehouseSchemaDatabase() }
        @JvmStatic @AfterAll fun stop() { database.close() }
        @JvmStatic fun familiesAndScopes(): Stream<Arguments> = WarehouseTimingFamily.entries.flatMap { family ->
            listOf("CORRECT","CLEARED","FOREIGN","STALE","RESTORED").map { mode -> Arguments.of(family,mode) }
        }.stream()
    }

    @ParameterizedTest
    @ValueSource(strings=["CORRECT","CLEARED","FOREIGN","STALE","RESTORED"])
    fun `early companion scope check cannot authorize a staged balance later`(mode: String) {
        val stock=WarehouseLotCapacityFixture(database)
        stock.create(40)
        val staged=UUID.randomUUID()
        val foreign=foreignTenant()
        database.ownerFixture { connection ->
            connection.autoCommit=false
            connection.sql("SET LOCAL app.tenant_id='${stock.tenant}'")
            connection.sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base,warehouse_admission) VALUES ('$staged','${stock.tenant}','${stock.sku}','${stock.lot}','REEL','MM',1,'LEGACY_UNRESOLVED')")
            connection.commit()
        }
        val failure=runCatching {
            stock.open().use { connection ->
                connection.sql("INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,location_id,custody_owner_id,custody_owner_kind,status,rebuilt_at,quantity_base,base_unit,stock_identity_id,lot_id,condition,legal_owner) VALUES ('${UUID.randomUUID()}','${stock.tenant}','$staged','${stock.sku}','${stock.location}','${stock.location}','WAREHOUSE','AVAILABLE',now(),1,'MM','$staged','${stock.lot}','SERVICEABLE','ISP')")
                connection.sql("SET CONSTRAINTS warehouse_aaa_deferred_tenant_scope IMMEDIATE")
                changeScope(connection,mode,stock.tenant,foreign)
                connection.commit()
            }
        }.exceptionOrNull()
        val totals=stock.totals()
        println("selective companion $mode commit=${(failure as? SQLException)?.sqlState ?: "00000"} fresh=$totals")
        assertThat(failure).isInstanceOf(SQLException::class.java)
        assertThat((failure as SQLException).sqlState).isEqualTo("23514")
        assertThat(totals).containsExactly(40L,40L,40L)
    }

    @ParameterizedTest
    @MethodSource("familiesAndScopes")
    internal fun `each actual validator checks scope independently after all other constraints ran`(family: WarehouseTimingFamily, mode: String) {
        WarehouseTimingFixture(database,family.serial).use { fixture ->
            fixture.prepare(family,true)
            fixture.holdOnly(family.constraint)
            changeScope(fixture.connection,mode,fixture.stock.tenant,foreignTenant())
            if (mode !in setOf("CORRECT","RESTORED")) {
                val failure=assertThrows<SQLException> { fixture.connection.commit() }
                assertThat(failure.sqlState).isEqualTo("23514")
                assertThat(failure.message).contains("row tenant scope")
            } else fixture.connection.commit()
        }
    }

    @ParameterizedTest
    @EnumSource(value=WarehouseTimingFamily::class,names=["PROVENANCE","SOURCE","CLAIM","ORIGIN","CONSERVATION","USAGE","CAPACITY","LOCATION"])
    internal fun `invalid data fails when its actual invariant is forced immediate`(family: WarehouseTimingFamily) {
        WarehouseTimingFixture(database,family.serial).use { fixture ->
            fixture.prepare(family,false)
            val failure=assertThrows<SQLException> { fixture.stock.sql("SET CONSTRAINTS ${family.constraint} IMMEDIATE") }
            assertThat(failure.sqlState).isEqualTo(family.invalidState)
        }
    }

    @ParameterizedTest
    @ValueSource(strings=["CLEARED","FOREIGN"])
    fun `earlier source validation does not cover a later deferred claim mutation`(mode: String) {
        WarehouseTimingFixture(database,true).use { fixture ->
            fixture.prepare(WarehouseTimingFamily.SOURCE,true)
            fixture.stock.sql("SET CONSTRAINTS warehouse_verified_source IMMEDIATE")
            fixture.stock.sql("SET CONSTRAINTS warehouse_verified_source DEFERRED")
            fixture.stock.sql("UPDATE inventory_identity_claim SET state='RETIRED',revision=2 WHERE id='${fixture.claim}'")
            fixture.holdOnly("warehouse_verified_source")
            changeScope(fixture.connection,mode,fixture.stock.tenant,foreignTenant())
            val failure=assertThrows<SQLException> { fixture.connection.commit() }
            assertThat(failure.sqlState).isEqualTo("23514")
            assertThat(failure.message).contains("row tenant scope")
        }
    }

    @Test
    fun `catalog covers every deferrable warehouse validator with an internal entry assertion`() {
        database.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT DISTINCT p.proname,p.prosrc,p.prosecdef FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid
                JOIN pg_class c ON c.oid=t.tgrelid WHERE c.relnamespace=current_schema()::regnamespace
                AND t.tgdeferrable AND NOT t.tgisinternal AND t.tgname LIKE 'warehouse_%'
            """.trimIndent()).use { rows ->
                val names=mutableSetOf<String>()
                while (rows.next()) {
                    names.add(rows.getString(1))
                    assertThat(rows.getBoolean(3)).isFalse()
                    assertThat(rows.getString(2).substringAfter("BEGIN").trimStart())
                        .describedAs(rows.getString(1)).startsWith("PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);")
                }
                assertThat(names).containsExactlyInAnyOrderElementsOf(WarehouseTimingFamily.entries.map { it.function } +
                    listOf("warehouse_approval_terminal_guard", "warehouse_approval_decision_binding", "warehouse_material_submission_binding_guard",
                        "warehouse_live_issue_binding_guard", "warehouse_issue_dispatch_binding_guard", "warehouse_material_receipt_guard"))
            }
        } }
    }

    @Test
    fun `V174_4 upgrade guards pending validators and validates a no op rerun`() {
        WarehouseSchemaDatabase("174.4").use { upgraded ->
            val stock=WarehouseLotCapacityFixture(upgraded)
            stock.create(40)
            assertThat(upgraded.migrate("174.5").migrationsExecuted).isEqualTo(1)
            stock.open().use { connection ->
                connection.sql("UPDATE inventory_balance_projection SET quantity_base=39,revision=1 WHERE stock_identity_id='${stock.firstRoot}'")
                connection.sql("SET CONSTRAINTS warehouse_aaa_deferred_tenant_scope IMMEDIATE")
                connection.sql("SET LOCAL app.tenant_id=''")
                assertThat(assertThrows<SQLException> { connection.commit() }.sqlState).isEqualTo("23514")
            }
            assertThat(stock.totals()).containsExactly(40L,40L,40L)
            assertThat(upgraded.migrate("174.5").migrationsExecuted).isZero()
        }
    }

    private fun foreignTenant(): UUID = UUID.randomUUID().also { tenant ->
        database.dataSource.connection.use { it.sql("INSERT INTO tenant(id,slug,name) VALUES ('$tenant','timing-$tenant','Timing foreign')") }
    }

    private fun changeScope(connection: java.sql.Connection, mode: String, correct: UUID, foreign: UUID) {
        when(mode) {
            "CORRECT" -> Unit
            "CLEARED" -> connection.sql("SET LOCAL app.tenant_id=''")
            "FOREIGN" -> connection.sql("SET LOCAL app.tenant_id='$foreign'")
            "STALE" -> connection.sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
            "RESTORED" -> { connection.sql("SET LOCAL app.tenant_id='$foreign'"); connection.sql("SET LOCAL app.tenant_id='$correct'") }
            else -> error("Unknown timing case")
        }
    }
}
