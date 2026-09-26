package com.duluin.ftth.inventory.adapter.outbound.persistence

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import javax.sql.DataSource

/** Missing clock capabilities are an unsupported schema, never a live-draft fallback. */
@Component
@Lazy(false)
@DependsOnDatabaseInitialization
class WarehouseDraftSchemaGuard(dataSource: DataSource) {
    init {
        dataSource.connection.use { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("""
                WITH required_tables(name) AS (VALUES
                    ('inventory_draft_policy'),('inventory_document_draft_activity'),('inventory_plan_draft_activity'),
                    ('inventory_document_draft_expiry'),('inventory_plan_draft_expiry'),('inventory_receipt_draft_command')),
                required_functions(name) AS (VALUES
                    ('warehouse_has_draft_clock'),('warehouse_draft_policy_current'),('warehouse_draft_policy_capture'),
                    ('warehouse_document_draft_expired_at'),('warehouse_plan_draft_expired_at'),
                    ('warehouse_document_current_state'),('warehouse_plan_current_state'),
                    ('warehouse_assert_document_draft_live'),('warehouse_assert_plan_draft_live'),
                    ('warehouse_draft_created'),('warehouse_draft_saved'),('warehouse_receipt_draft_save_seal'),
                    ('warehouse_draft_document_write_guard'),('warehouse_draft_plan_write_guard'),
                    ('warehouse_assert_idle_source_effects'),('warehouse_idle_source_effect_guard'),
                    ('warehouse_draft_decision_guard'),('warehouse_draft_expiry_final_guard'),
                    ('warehouse_expire_document_draft'),('warehouse_expire_plan_draft')),
                required_triggers(relation,name) AS (VALUES
                    ('inventory_document','warehouse_draft_created'),('inventory_material_plan','warehouse_draft_created'),
                    ('inventory_operation','warehouse_draft_saved'),
                    ('inventory_document_draft_activity','warehouse_receipt_draft_save_seal'),
                    ('inventory_document_draft_expiry','warehouse_draft_expiry_final'),
                    ('inventory_document','warehouse_idle_source_effect'),('inventory_movement','warehouse_idle_source_effect'),
                    ('inventory_approval_effect','warehouse_idle_source_effect'),
                    ('inventory_document','warehouse_aaa_draft_live'),('inventory_document_line','warehouse_aaa_draft_live'),
                    ('inventory_receipt_intake','warehouse_aaa_draft_live'),('inventory_receipt_evidence','warehouse_aaa_draft_live'),
                    ('inventory_count_scope','warehouse_aaa_draft_live'),('inventory_count_entry','warehouse_aaa_draft_live'),
                    ('inventory_material_plan','warehouse_aaa_draft_live'),('inventory_material_plan_line','warehouse_aaa_draft_live'),
                    ('inventory_material_submission','warehouse_aaa_draft_live'),('inventory_approval','warehouse_aaa_draft_live'),
                    ('inventory_movement','warehouse_aaa_draft_live'),('inventory_approval_effect','warehouse_aaa_draft_live'),
                    ('inventory_approval_decision','warehouse_aaa_draft_live'),
                    ('inventory_draft_policy','warehouse_draft_policy_capture'),('inventory_draft_policy','warehouse_draft_policy_immutable'),
                    ('inventory_draft_policy','warehouse_draft_policy_tenant_cleanup'),
                    ('inventory_document_draft_activity','warehouse_draft_immutable'),('inventory_plan_draft_activity','warehouse_draft_immutable'),
                    ('inventory_document_draft_expiry','warehouse_draft_immutable'),('inventory_plan_draft_expiry','warehouse_draft_immutable'),
                    ('inventory_receipt_draft_command','warehouse_append_only'))
                SELECT NOT EXISTS(SELECT FROM required_tables expected WHERE NOT EXISTS(
                    SELECT FROM pg_class actual WHERE actual.relnamespace=current_schema()::regnamespace
                    AND actual.relname=expected.name AND actual.relrowsecurity AND actual.relforcerowsecurity))
                AND NOT EXISTS(SELECT FROM required_functions expected WHERE NOT EXISTS(
                    SELECT FROM pg_proc actual WHERE actual.pronamespace=current_schema()::regnamespace AND actual.proname=expected.name))
                AND NOT EXISTS(SELECT FROM required_triggers expected WHERE NOT EXISTS(
                    SELECT FROM pg_trigger actual WHERE actual.tgrelid=to_regclass(quote_ident(current_schema())||'.'||quote_ident(expected.relation))
                    AND actual.tgname=expected.name AND NOT actual.tgisinternal AND actual.tgenabled IN ('O','A')))
            """.trimIndent()).use { rows ->
                check(rows.next() && rows.getBoolean(1)) {
                    "Warehouse idle draft schema is incomplete; apply and verify the complete current migrations before starting this application"
                }
            }
        } }
    }
}
