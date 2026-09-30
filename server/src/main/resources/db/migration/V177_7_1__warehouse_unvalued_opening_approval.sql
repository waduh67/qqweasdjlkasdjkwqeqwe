-- All ordinary approvals still require exact value and currency. A migration
-- opening has unknown historical valuation and must pass the sealed source/all-
-- tiers guard introduced in V177.7; it may never masquerade as a zero value.
ALTER TABLE inventory_approval DROP CONSTRAINT warehouse_approval_exact_source;
ALTER TABLE inventory_approval ADD CONSTRAINT warehouse_approval_exact_source CHECK(source_document_id IS NULL OR
    (policy_version_id IS NOT NULL AND source_snapshot_hash IS NOT NULL AND source_snapshot_hash ~ '^[0-9a-f]{64}$'
    AND business_action IS NOT NULL AND btrim(business_action)<>''
    AND ((business_action='OPENING_BALANCE' AND amount IS NULL AND currency IS NULL AND value_numerator IS NULL AND value_denominator IS NULL)
        OR (business_action<>'OPENING_BALANCE' AND currency IS NOT NULL AND value_numerator IS NOT NULL
            AND value_numerator>=0 AND value_denominator IS NOT NULL))
    AND independence_snapshot IS NOT NULL AND authority_epoch IS NOT NULL AND authority_epoch>=0));
