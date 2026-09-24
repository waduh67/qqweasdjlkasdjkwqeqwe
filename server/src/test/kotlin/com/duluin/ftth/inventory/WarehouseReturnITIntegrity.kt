package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseReturnITIntegrity : WarehouseReturnAssetFixture() {
    @Test fun `received recovery cannot authorize an unposted asset and balance condition rewrite`() {
        val returned = recoveredReturn(release = true)
        val receipt = returned.old.installation.receipt
        val asset = receipt.input.lines.single().stockIdentityId
        fixture(receipt.stock.token).transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            sql("SAVEPOINT return_rewrite_probe")
            val rejection = runCatching {
                sql("""UPDATE inventory_balance_projection SET condition='DAMAGED',status='QUARANTINE',revision=revision+1
                    WHERE stock_identity_id='$asset' AND quantity_base>0""")
                sql("""UPDATE inventory_serialized_asset SET condition='DAMAGED',status='QUARANTINE',revision=revision+1
                    WHERE id='$asset'""")
                sql("SET CONSTRAINTS ALL IMMEDIATE")
            }.exceptionOrNull()
            sql("ROLLBACK TO SAVEPOINT return_rewrite_probe")
            assertThat(rejection).withFailMessage("A sealed receipt must not authorize a later unposted physical rewrite").isNotNull()
            assertThat(generateSequence(rejection) { it.cause }.filterIsInstance<java.sql.SQLException>().any { it.sqlState == "23514" }).isTrue()
        }
    }
}
