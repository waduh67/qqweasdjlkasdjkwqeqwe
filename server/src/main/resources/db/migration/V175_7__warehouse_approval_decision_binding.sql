CREATE FUNCTION warehouse_approval_decision_binding() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE approval inventory_approval%ROWTYPE; requirement inventory_approval_requirement%ROWTYPE;
    delegation inventory_approval_delegation%ROWTYPE; policy_tier jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.approval_id FOR UPDATE;
    IF approval.id IS NULL THEN RAISE EXCEPTION 'approval source is missing' USING ERRCODE='23514'; END IF;
    IF approval.evaluation_snapshot IS NULL THEN
        IF approval.source_document_id IS NOT NULL OR approval.policy_version_id IS NOT NULL OR NEW.policy_version_id IS NOT NULL THEN
            RAISE EXCEPTION 'legacy decision requires an explicitly unbound legacy request' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.policy_version_id IS NULL OR NEW.policy_version_id IS DISTINCT FROM approval.policy_version_id THEN
        RAISE EXCEPTION 'decision policy must match executable request' USING ERRCODE='23514';
    END IF;
    SELECT * INTO requirement FROM inventory_approval_requirement
        WHERE tenant_id=NEW.tenant_id AND approval_id=NEW.approval_id AND tier=NEW.tier;
    IF requirement.id IS NULL THEN RAISE EXCEPTION 'decision tier is not required' USING ERRCODE='23514'; END IF;
    IF TG_WHEN='BEFORE' AND (approval.status<>'PENDING' OR NEW.revision<>approval.revision+1 OR approval.expires_at<=clock_timestamp()) THEN
        RAISE EXCEPTION 'decision requires current pending revision and unexpired request' USING ERRCODE='23514';
    END IF;
    IF NEW.approver_id IN (approval.requester_id,approval.custodian_id,approval.counter_id)
        OR approval.independence_snapshot ? NEW.approver_id::text
        OR NEW.delegated_from IN (approval.requester_id,approval.custodian_id,approval.counter_id)
        OR approval.independence_snapshot ? NEW.delegated_from::text
        OR NEW.approver_id=NEW.delegated_from THEN
        RAISE EXCEPTION 'decision actor must be independent' USING ERRCODE='23514';
    END IF;
    IF NEW.independence_snapshot IS NULL OR jsonb_typeof(NEW.independence_snapshot)<>'object'
        OR NEW.independence_snapshot->>'id' IS DISTINCT FROM NEW.id::text
        OR NEW.independence_snapshot->>'actorId' IS DISTINCT FROM NEW.approver_id::text
        OR NEW.independence_snapshot->>'tier' IS DISTINCT FROM NEW.tier::text
        OR NEW.independence_snapshot->>'decision' IS DISTINCT FROM NEW.decision
        OR NEW.independence_snapshot->>'revision' IS DISTINCT FROM NEW.revision::text
        OR (NEW.independence_snapshot->>'decidedAt')::timestamptz IS DISTINCT FROM NEW.decided_at
        OR NEW.authority_epoch IS NULL OR NEW.independence_snapshot->>'authorityEpoch' IS DISTINCT FROM NEW.authority_epoch::text
        OR NEW.independence_snapshot->>'reason' IS DISTINCT FROM NEW.reason
        OR NEW.independence_snapshot->>'evidenceReference' IS DISTINCT FROM NEW.evidence_reference::text THEN
        RAISE EXCEPTION 'decision evidence must match its immutable columns' USING ERRCODE='23514';
    END IF;
    IF (NEW.delegation_id IS NULL)<>(NEW.delegated_from IS NULL) THEN
        RAISE EXCEPTION 'delegation identity must be complete' USING ERRCODE='23514';
    END IF;
    IF NEW.delegation_id IS NULL THEN
        IF coalesce(NEW.independence_snapshot->'delegation','null'::jsonb)<>'null'::jsonb OR NOT EXISTS (
            SELECT FROM jsonb_array_elements(requirement.candidates) candidate
            WHERE candidate->>'userId'=NEW.approver_id::text AND candidate->>'delegatedFrom' IS NULL
        ) THEN RAISE EXCEPTION 'actor is not a sealed direct candidate' USING ERRCODE='23514'; END IF;
    ELSE
        SELECT * INTO delegation FROM inventory_approval_delegation WHERE tenant_id=NEW.tenant_id AND id=NEW.delegation_id;
        SELECT rule->'tiers'->(NEW.tier-1) INTO policy_tier FROM jsonb_array_elements(approval.policy_snapshot->'rules') rule
            WHERE rule->>'operation'=approval.business_action;
        IF delegation.id IS NULL OR delegation.approver_id<>NEW.delegated_from OR delegation.delegate_id<>NEW.approver_id
            OR delegation.operation<>approval.business_action OR delegation.valid_from>NEW.decided_at OR delegation.valid_until<=NEW.decided_at
            OR (delegation.revoked_at IS NOT NULL AND delegation.revoked_at<=NEW.decided_at)
            OR (TG_WHEN='BEFORE' AND (delegation.revoked_at IS NOT NULL OR delegation.valid_until<=clock_timestamp()))
            OR NEW.independence_snapshot#>>'{delegation,id}' IS DISTINCT FROM delegation.id::text
            OR NEW.independence_snapshot#>>'{delegation,approverId}' IS DISTINCT FROM delegation.approver_id::text
            OR NEW.independence_snapshot#>>'{delegation,delegateId}' IS DISTINCT FROM delegation.delegate_id::text
            OR NEW.independence_snapshot#>>'{delegation,sourceRoleId}' IS DISTINCT FROM delegation.source_role_id::text
            OR NEW.independence_snapshot#>>'{delegation,locationId}' IS DISTINCT FROM delegation.location_id::text
            OR (NEW.independence_snapshot#>>'{delegation,validFrom}')::timestamptz IS DISTINCT FROM delegation.valid_from
            OR (NEW.independence_snapshot#>>'{delegation,validUntil}')::timestamptz IS DISTINCT FROM delegation.valid_until
            OR NOT EXISTS (SELECT FROM jsonb_array_elements(requirement.candidates) candidate
                WHERE candidate->>'userId'=NEW.delegated_from::text AND candidate->>'delegatedFrom' IS NULL)
            OR (delegation.source_role_id IS NULL AND NOT coalesce(policy_tier->'userIds' ? NEW.delegated_from::text,false))
            OR (delegation.source_role_id IS NOT NULL AND NOT coalesce(policy_tier->'roleIds' ? delegation.source_role_id::text,false)) THEN
            RAISE EXCEPTION 'decision delegation is not bound to a sealed eligible source' USING ERRCODE='23514';
        END IF;
        IF EXISTS (SELECT FROM jsonb_array_elements(approval.source_snapshot::jsonb->'lines') line WHERE NOT EXISTS (
            WITH RECURSIVE ancestry AS (
                SELECT id,parent_location_id FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=(line->>'location_id')::uuid
                UNION SELECT parent.id,parent.parent_location_id FROM inventory_location parent JOIN ancestry ON ancestry.parent_location_id=parent.id
                    WHERE parent.tenant_id=NEW.tenant_id
            ) SELECT FROM ancestry WHERE id=delegation.location_id
        )) THEN RAISE EXCEPTION 'delegation scope does not cover source' USING ERRCODE='23514'; END IF;
    END IF;
    IF EXISTS (SELECT FROM inventory_approval_decision previous WHERE previous.tenant_id=NEW.tenant_id AND previous.approval_id=NEW.approval_id
        AND previous.id<>NEW.id AND (previous.tier=NEW.tier OR previous.revision=NEW.revision
            OR previous.approver_id IN (NEW.approver_id,NEW.delegated_from)
            OR previous.delegated_from IN (NEW.approver_id,NEW.delegated_from))) THEN
        RAISE EXCEPTION 'independent tier identities cannot be reused' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_approval_decision_insert BEFORE INSERT ON inventory_approval_decision
    FOR EACH ROW EXECUTE FUNCTION warehouse_approval_decision_binding();
CREATE CONSTRAINT TRIGGER warehouse_approval_decision_bound AFTER INSERT ON inventory_approval_decision
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_approval_decision_binding();
