package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.WarehousePosting
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehouseReturnITIntegrity : WarehouseReturnAssetFixture() {
    @Test fun `returned physical position survives an atomic projection rebuild without new movements`() {
        val returned = recoveredReturn(release = true)
        val receipt = returned.old.installation.receipt
        val asset = receipt.input.lines.single().stockIdentityId
        val scoped = fixture(receipt.stock.token)
        val movements = scoped.transaction { scalar("SELECT count(*) FROM inventory_movement") }
        scoped.transaction {
            sql("DELETE FROM inventory_balance_projection WHERE stock_identity_id='$asset'")
            context.getBean(WarehousePosting::class.java).rebuild(0)
        }
        scoped.transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo(movements)
            assertThat(scalar("SELECT concat_ws('|',quantity_base,status,condition,legal_owner) FROM inventory_balance_projection WHERE stock_identity_id='$asset' AND quantity_base>0"))
                .isEqualTo("1|AVAILABLE|SERVICEABLE|ISP")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CONDITION", "TITLE"])
    fun `received recovery cannot authorize an unposted asset and balance rewrite`(change: String) {
        val returned = if (change == "TITLE") recoveredReturn("SALE") else recoveredReturn(release = true)
        val receipt = returned.old.installation.receipt
        val asset = receipt.input.lines.single().stockIdentityId
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            jdbc { connection ->
                val savepoint = connection.setSavepoint()
                // Catch inside doReturningWork: expected SQL rejection must not
                // mark Hibernate's surrounding transaction rollback-only.
                val rejection = runCatching {
                    connection.createStatement().use { statement ->
                        val mutation = if (change == "TITLE") "legal_owner='ISP'" else "condition='DAMAGED',status='QUARANTINE'"
                        statement.execute("""UPDATE inventory_balance_projection SET $mutation,revision=revision+1
                            WHERE stock_identity_id='$asset' AND quantity_base>0""")
                        statement.execute("""UPDATE inventory_serialized_asset SET $mutation,revision=revision+1
                            WHERE id='$asset'""")
                        statement.execute("SET CONSTRAINTS ALL IMMEDIATE")
                    }
                }.exceptionOrNull()
                connection.rollback(savepoint)
                assertThat(rejection).withFailMessage("A sealed receipt must not authorize a later unposted physical rewrite").isNotNull()
                assertThat(generateSequence(rejection) { it.cause }.filterIsInstance<java.sql.SQLException>().any { it.sqlState == "23514" }).isTrue()
            }
        }
    }
}
