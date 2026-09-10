package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehousePolicyITSchema {
    @Test
    fun `policy children history RLS and tenant foreign keys are durable constraints`() {
        WarehouseSchemaDatabase().use { database -> database.dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                WarehouseSchemaFixture(connection).apply {
                    masters()
                    sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash) VALUES ('$actor','$tenant','policy@test','Policy','unused')")
                    val policy = UUID.randomUUID()
                    sql("""INSERT INTO inventory_approval_policy_version(id,tenant_id,revision,currency,expiry_hours,actor_id,authority_epoch,snapshot,snapshot_hash)
                        VALUES ('$policy','$tenant',1,'IDR',24,'$actor',0,'{}','${"a".repeat(64)}')""")
                    sql("INSERT INTO inventory_approval_policy_warehouse(id,tenant_id,policy_id,location_id) VALUES ('${UUID.randomUUID()}','$tenant','$policy','$location')")
                    reject("23514", "UPDATE inventory_approval_policy_version SET currency='USD' WHERE id='$policy'")
                    reject("23514", "DELETE FROM inventory_approval_policy_version WHERE id='$policy'")
                    reject("23503", "INSERT INTO inventory_approval_policy_warehouse(id,tenant_id,policy_id,location_id) VALUES ('${UUID.randomUUID()}','$tenant','$policy','${UUID.randomUUID()}')")
                    assertThat(scalar("SELECT count(*) FROM inventory_approval_decision")).isEqualTo("0")
                    assertThat(scalar("""SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND is_nullable='YES' AND
                        ((table_name='inventory_approval' AND column_name='amount') OR
                        (table_name='inventory_cycle_count' AND column_name IN ('prior_quantity','observed_quantity')))""")).isEqualTo("3")
                    sql("SET LOCAL app.tenant_id='${UUID.randomUUID()}'")
                    assertThat(scalar("SELECT count(*) FROM inventory_approval_policy_version")).isEqualTo("0")
                }
            } finally { connection.rollback() }
        } }
    }
}
