package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.SQLException

class WarehouseApprovalITSchema : WarehouseApprovalHttpFixture() {
    @Test fun `request snapshots decisions and effects cannot be edited or reassigned`() {
        val case = pending()
        assertThat(decide(case).status).isEqualTo(200)
        fixture(case.setup.token).transaction {
            jdbc { connection ->
                for (query in listOf(
                    "UPDATE inventory_approval SET expires_at=expires_at+interval '1 day',revision=revision+1",
                    "UPDATE inventory_approval SET source_document_revision=source_document_revision+1,revision=revision+1",
                    "UPDATE inventory_approval_requirement SET candidates='[]'::jsonb",
                    "UPDATE inventory_approval_decision SET reason='rewritten'",
                    "DELETE FROM inventory_approval_effect",
                    "UPDATE inventory_approval_command SET original_body='{}'"
                )) {
                    val point = connection.setSavepoint()
                    try { assertThatThrownBy { connection.createStatement().use { it.execute(query) } }.isInstanceOf(SQLException::class.java) }
                    finally { connection.rollback(point) }
                }
                assertThat(scalar("SELECT count(*) FROM pg_class WHERE relname IN ('inventory_approval_requirement','inventory_approval_command') AND relrowsecurity AND relforcerowsecurity AND relnamespace=current_schema()::regnamespace")).isEqualTo("2")
                sql("SET LOCAL app.tenant_id='${java.util.UUID.randomUUID()}'")
                assertThat(scalar("SELECT count(*) FROM inventory_approval")).isEqualTo("0")
                assertThat(scalar("SELECT count(*) FROM inventory_approval_command")).isEqualTo("0")
            }
        }
    }
}
