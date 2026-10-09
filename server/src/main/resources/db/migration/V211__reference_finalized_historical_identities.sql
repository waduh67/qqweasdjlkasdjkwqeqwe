-- Approved historical ONU identities stay reserved forever without becoming stock.
CREATE FUNCTION warehouse_reference_historical_identity(scope uuid,target uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS(
        SELECT FROM inventory_identity_claim claim
        JOIN inventory_tenant_cutover policy ON policy.tenant_id=claim.tenant_id AND policy.state='ENFORCED'
        JOIN inventory_migration_finalization finalized ON finalized.tenant_id=policy.tenant_id
            AND finalized.batch_id=policy.migration_batch_id AND finalized.resulting_epoch<=policy.epoch
        JOIN inventory_migration_admission admission ON admission.tenant_id=finalized.tenant_id
            AND admission.batch_id=finalized.batch_id AND admission.request_id=finalized.request_id
        JOIN inventory_migration_opening_request opening ON opening.tenant_id=admission.tenant_id
            AND opening.id=admission.request_id AND opening.batch_id=admission.batch_id AND opening.review_hash=admission.review_hash
        WHERE claim.tenant_id=scope AND claim.id=target AND claim.state='LEGACY_RESERVED' AND claim.admitted_asset_id IS NULL
            AND EXISTS(SELECT FROM inventory_identity_candidate candidate WHERE candidate.tenant_id=scope AND candidate.claim_id=claim.id)
            AND NOT EXISTS(
                SELECT FROM inventory_identity_candidate candidate WHERE candidate.tenant_id=scope AND candidate.claim_id=claim.id
                    AND NOT EXISTS(
                        SELECT FROM jsonb_array_elements(opening.review_manifest->'cases') member
                        JOIN inventory_provenance_case source ON source.tenant_id=scope AND source.id=(member->>'caseId')::uuid
                            AND source.source_table=member->>'sourceTable' AND source.source_id=(member->>'sourceId')::uuid
                            AND source.source_hash=member->>'sourceHash' AND source.source_snapshot=member->'sourceSnapshot'
                        JOIN inventory_migration_resolution resolution ON resolution.tenant_id=scope AND resolution.batch_id=finalized.batch_id
                            AND resolution.case_id=source.id AND resolution.source_hash=source.source_hash
                            AND resolution.id=(member->'resolution'->>'id')::uuid AND resolution.kind='PROVENANCE_ONLY'
                            AND resolution.original_body::jsonb=member->'resolution'
                        JOIN warehouse_current_legacy_identity current_identity ON current_identity.tenant_id=scope
                            AND current_identity.source_table=source.source_table AND current_identity.source_id=source.source_id
                            AND current_identity.identity_type=candidate.identity_type AND current_identity.raw_value=candidate.raw_value
                            AND current_identity.canonical_value=claim.canonical_value
                        WHERE candidate.source_table='onu' AND candidate.identity_type='SERIAL' AND claim.identity_type='SERIAL'
                            AND source.source_table=candidate.source_table AND source.source_id=candidate.source_id
                            AND source.source_snapshot->>'serialNumber'=candidate.raw_value
                            AND candidate.canonical_value=claim.canonical_value
                            AND warehouse_canonical_serial(candidate.raw_value)=claim.canonical_value)))
$$;

DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_reference_review(uuid)'::regprocedure);
    anchor:='EXISTS(SELECT FROM inventory_identity_claim WHERE tenant_id=scope AND state IN (''LEGACY_RESERVED'',''CONFLICT''))';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference identity review layout changed'; END IF;
    EXECUTE replace(definition,anchor,'EXISTS(SELECT FROM inventory_identity_claim WHERE tenant_id=scope AND
            (state=''CONFLICT'' OR state=''LEGACY_RESERVED'' AND NOT warehouse_reference_historical_identity(scope,id)))');
END $$;
