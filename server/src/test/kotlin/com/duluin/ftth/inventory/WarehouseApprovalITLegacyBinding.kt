package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseApprovalITLegacyBinding {
    @Test fun `forward binding migration preserves unbound legacy decisions without making them executable`() {
        WarehouseSchemaDatabase("175.2").use { database ->
            val approval = UUID.randomUUID()
            val decision = UUID.randomUUID()
            lateinit var tenant: UUID
            lateinit var actor: UUID
            database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                WarehouseSchemaFixture(connection).apply {
                    masters()
                    tenant = this.tenant
                    actor = this.actor
                    sql("INSERT INTO app_user(id,tenant_id,email,name,password_hash) VALUES ('$actor','$tenant','legacy@test','Legacy','unused')")
                    sql("""INSERT INTO inventory_approval(id,tenant_id,approval_type,amount,requester_id,policy_version,policy_snapshot,
                        policy_snapshot_hash,operation_key,operation_hash,requested_at,expires_at,status)
                        VALUES ('$approval','$tenant','ADJUSTMENT',1,'$actor',1,'{}','legacy','legacy-request','legacy',clock_timestamp(),clock_timestamp()+interval '1 day','PENDING')""")
                    sql("""INSERT INTO inventory_approval_decision(id,tenant_id,approval_id,tier,approver_id,decision,decided_at,revision,operation_key,operation_hash)
                        VALUES ('$decision','$tenant','$approval',999,'$actor','APPROVE',clock_timestamp(),1,'legacy-decision','legacy')""")
                }
                connection.commit()
            }
            database.migrate()
            database.dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute("SET app.tenant_id='$tenant'")
                statement.executeQuery("SELECT tier,policy_version_id FROM inventory_approval_decision WHERE id='$decision'").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getInt(1)).isEqualTo(999)
                    assertThat(rows.getObject(2)).isNull()
                }
                statement.executeQuery("SELECT source_document_id,evaluation_snapshot FROM inventory_approval WHERE id='$approval'").use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getObject(1)).isNull()
                    assertThat(rows.getObject(2)).isNull()
                }
                statement.execute("""INSERT INTO inventory_approval_decision(id,tenant_id,approval_id,tier,approver_id,decision,decided_at,revision,operation_key,operation_hash)
                    VALUES ('${UUID.randomUUID()}','$tenant','$approval',999,'$actor','REJECT',clock_timestamp(),2,'legacy-preserved','legacy')""")
            } }
        }
    }
}
