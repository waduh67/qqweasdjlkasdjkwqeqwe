ALTER TABLE inventory_approval_command ADD COLUMN attempted_decision jsonb;

CREATE FUNCTION warehouse_approval_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.namespace='decide' AND (NEW.attempted_decision IS NULL OR NOT EXISTS (
        SELECT FROM inventory_approval approval JOIN inventory_approval_requirement requirement
            ON requirement.tenant_id=approval.tenant_id AND requirement.approval_id=approval.id
        WHERE approval.tenant_id=NEW.tenant_id AND approval.id=NEW.approval_id
            AND NEW.attempted_decision->>'requestId'=approval.id::text
            AND NEW.attempted_decision->>'sourceDocumentId'=approval.source_document_id::text
            AND NEW.attempted_decision->>'sourceRevision'=approval.source_document_revision::text
            AND NEW.attempted_decision->>'policyVersionId'=approval.policy_version_id::text
            AND NEW.attempted_decision->>'tier'=requirement.tier::text
            AND NEW.attempted_decision->>'decision' IN ('APPROVE','REJECT')
            AND (NEW.attempted_decision->>'requestRevision') ~ '^[0-9]+$'
    )) THEN RAISE EXCEPTION 'decision command requires server-derived attempted tier context' USING ERRCODE='23514'; END IF;
    IF NEW.namespace<>'decide' AND NEW.attempted_decision IS NOT NULL THEN
        RAISE EXCEPTION 'attempt context belongs to decision commands' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_approval_attempt BEFORE INSERT ON inventory_approval_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_approval_attempt_guard();
