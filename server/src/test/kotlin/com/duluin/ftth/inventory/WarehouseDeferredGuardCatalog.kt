package com.duluin.ftth.inventory

/** Reviewed entry checks for every deferred warehouse trigger; new validators need an explicit entry. */
internal object WarehouseDeferredGuardCatalog {
    fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ")

    val expected: Map<String, String> = buildMap {
        listOf(
            "warehouse_approval_decision_binding",
            "warehouse_approval_terminal_guard",
            "warehouse_asset_claim_guard",
            "warehouse_asset_episode_final_guard",
            "warehouse_check_lot_root_capacity",
            "warehouse_consumed_balance_guard",
            "warehouse_count_observation_command_guard",
            "warehouse_count_result_guard",
            "warehouse_deferred_scope_guard",
            "warehouse_fulfillment_deployments_guard",
            "warehouse_issue_dispatch_binding_guard",
            "warehouse_live_issue_binding_guard",
            "warehouse_location_tree_guard",
            "warehouse_material_close_final_guard",
            "warehouse_material_lifecycle_final_guard",
            "warehouse_material_obligation_origin_guard",
            "warehouse_material_receipt_guard",
            "warehouse_material_rework_final_guard",
            "warehouse_material_snapshot_set_guard",
            "warehouse_material_submission_binding_guard",
            "warehouse_material_usage_bound_guard",
            "warehouse_migration_admission_complete",
            "warehouse_migration_approval_snapshot_guard",
            "warehouse_migration_cancellations_complete",
            "warehouse_migration_finalization_complete",
            "warehouse_origin_guard",
            "warehouse_relocation_final_guard",
            "warehouse_repair_final_guard",
            "warehouse_rma_consumption_guard",
            "warehouse_rma_final_guard",
            "warehouse_segment_conservation",
            "warehouse_source_provenance_guard",
            "warehouse_stock_provenance_guard",
            "warehouse_usage_postings_guard",
            "warehouse_verified_reference_guard"
        ).forEach { put(it, normalized("""
            PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
        """)) }
        listOf(
            "warehouse_asset_handover_final_guard",
            "warehouse_asset_removal_final_guard",
            "warehouse_authorization_final_guard",
            "warehouse_authorization_source_guard",
            "warehouse_consumed_truth_guard",
            "warehouse_deployment_document_final_guard",
            "warehouse_deployment_fact_final_guard",
            "warehouse_deployment_final_guard",
            "warehouse_deployment_posting_final_guard",
            "warehouse_fulfillment_bound_guard",
            "warehouse_fulfillment_owner_guard",
            "warehouse_onu_episode_final_guard",
            "warehouse_residual_position_final_guard",
            "warehouse_title_final_guard"
        ).forEach { put(it, normalized("""
            PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
        """)) }
        listOf(
            "warehouse_asset_loss_request_final_guard",
            "warehouse_compensation_final_guard",
            "warehouse_disposition_final_guard",
            "warehouse_replacement_receipt_final_guard"
        ).forEach { put(it, normalized("""
            FOR value IN SELECT item FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
            WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) item LOOP
            scope:=(value->>'tenant_id')::uuid;
            PERFORM warehouse_assert_deferred_scope(scope);
        """)) }
        listOf(
            "warehouse_replacement_request_final_guard",
            "warehouse_return_title_final_guard"
        ).forEach { put(it, normalized("""
            value:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
            scope:=(value->>'tenant_id')::uuid;
            PERFORM warehouse_assert_deferred_scope(scope);
        """)) }
        listOf(
            "warehouse_return_final_guard"
        ).forEach { put(it, normalized("""
            data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
            scope:=(data->>'tenant_id')::uuid;
            PERFORM warehouse_assert_deferred_scope(scope);
        """)) }
        listOf(
            "warehouse_return_title_effect_final_guard"
        ).forEach { put(it, normalized("""
            FOR row_data IN SELECT value FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
            WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) LOOP
            scope:=(row_data->>'tenant_id')::uuid;
            PERFORM warehouse_assert_deferred_scope(scope);
        """)) }
        listOf(
            "warehouse_transfer_final_guard"
        ).forEach { put(it, normalized("""
            PERFORM warehouse_assert_deferred_scope(coalesce(NEW.tenant_id,OLD.tenant_id));
        """)) }
    }
}
