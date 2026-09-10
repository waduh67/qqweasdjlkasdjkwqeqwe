package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("test")
class WarehouseSchemaIT {
    @Autowired private lateinit var dataSource: DataSource

    private val tables = listOf(
        "inventory_sku", "inventory_uom_conversion", "inventory_supplier", "inventory_tenant_cutover",
        "inventory_identity_claim", "inventory_identity_candidate", "iam_authorization_epoch",
        "inventory_lot", "inventory_segment", "inventory_document", "inventory_document_line",
        "inventory_operation", "inventory_outbox", "inventory_inbox", "inventory_reservation",
        "inventory_material_plan", "inventory_material_plan_line", "inventory_usage_snapshot",
        "inventory_inspection", "inventory_warehouse_scope",
        "inventory_command_identity", "inventory_outbox_delivery",
        "fulfillment_warehouse_observation",
        "inventory_receipt_intake", "inventory_receipt_evidence", "inventory_receipt_disposition",
        "inventory_reservation_allocation", "inventory_demand_supply_snapshot",
        "inventory_material_template", "inventory_material_template_current", "inventory_material_plan_snapshot",
        "inventory_material_submission", "inventory_material_command",
    )

    private fun strings(sql: String): List<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    @Test
    fun `M01 and M02 create every reserved table`() {
        assertThat(strings("SELECT tablename FROM pg_tables WHERE schemaname='public'")).containsAll(tables)
    }

    @Test
    fun `every new tenant table forces RLS with read and write policies`() {
        assertThat(strings("""
            SELECT relname FROM pg_class JOIN pg_namespace ON pg_namespace.oid=relnamespace
            WHERE nspname='public' AND relrowsecurity AND relforcerowsecurity
              AND EXISTS (SELECT FROM pg_policy WHERE polrelid=pg_class.oid AND polqual IS NOT NULL AND polwithcheck IS NOT NULL)
        """.trimIndent())).containsAll(tables)
    }

    @Test
    fun `tenant parent foreign keys are composite`() {
        assertThat(strings("""
            SELECT DISTINCT conrelid::regclass::text FROM pg_constraint
            WHERE contype='f' AND cardinality(conkey)>=2
        """.trimIndent())).contains("inventory_document_line", "inventory_reservation", "inventory_segment", "inventory_command_identity", "inventory_outbox_delivery",
            "inventory_receipt_intake", "inventory_receipt_evidence", "inventory_receipt_disposition",
            "inventory_reservation_allocation", "inventory_demand_supply_snapshot")
    }

    @Test
    fun `legacy raw quantity coexists with nullable exact dimensions`() {
        assertThat(strings("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema='public' AND table_name='inventory_balance_projection'
        """.trimIndent())).contains("quantity", "quantity_base", "base_unit", "stock_identity_id", "lot_id", "warehouse_admission")
    }

    @Test
    fun `identity and business action uniqueness are unconditional`() {
        assertThat(strings("SELECT indexname FROM pg_indexes WHERE schemaname='public'"))
            .contains("inventory_identity_claim_key_uq", "inventory_operation_action_uq", "inventory_balance_stock_dimension_uq")
    }

    @Test
    fun `posted outcomes and events have append only triggers`() {
        assertThat(strings("""
            SELECT DISTINCT event_object_table FROM information_schema.triggers
            WHERE trigger_schema='public' AND trigger_name LIKE '%append_only%'
        """.trimIndent())).contains("inventory_operation", "inventory_outbox", "inventory_inbox", "inventory_usage_snapshot", "inventory_command_identity",
            "inventory_receipt_evidence", "inventory_receipt_disposition", "inventory_reservation_allocation", "inventory_demand_supply_snapshot")
    }
}
