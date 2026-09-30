ALTER TABLE inventory_approval ALTER COLUMN amount DROP NOT NULL,
    ADD CONSTRAINT warehouse_approval_exact_source CHECK(source_document_id IS NULL OR
        (policy_version_id IS NOT NULL AND source_snapshot_hash IS NOT NULL AND source_snapshot_hash ~ '^[0-9a-f]{64}$'
        AND business_action IS NOT NULL AND btrim(business_action)<>'' AND currency IS NOT NULL
        AND value_numerator IS NOT NULL AND value_numerator>=0 AND value_denominator IS NOT NULL
        AND independence_snapshot IS NOT NULL AND authority_epoch IS NOT NULL AND authority_epoch>=0)),
    ADD CONSTRAINT warehouse_approval_legacy_amount CHECK(amount IS NOT NULL OR source_document_id IS NOT NULL);

ALTER TABLE inventory_cycle_count ALTER COLUMN prior_quantity DROP NOT NULL,
    ALTER COLUMN observed_quantity DROP NOT NULL,
    ADD CONSTRAINT warehouse_count_legacy_quantity CHECK(
        (prior_quantity IS NOT NULL AND observed_quantity IS NOT NULL) OR
        (document_id IS NOT NULL AND prior_quantity_base IS NOT NULL AND observed_quantity_base IS NOT NULL));

CREATE INDEX warehouse_approval_policy_lookup_idx ON inventory_approval(tenant_id,policy_version_id,source_document_id);
CREATE INDEX warehouse_count_approval_lookup_idx ON inventory_cycle_count(tenant_id,approval_id,document_id);
CREATE INDEX warehouse_repair_state_idx ON inventory_repair_case(tenant_id,state,created_at,id);
CREATE INDEX warehouse_replenishment_state_idx ON inventory_replenishment_request(tenant_id,state,created_at,id);
