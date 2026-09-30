CREATE OR REPLACE FUNCTION warehouse_policy_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT FROM inventory_approval_policy_version WHERE tenant_id=NEW.tenant_id AND id=NEW.policy_id AND created_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'policy history is sealed' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='inventory_approval_policy_approver' THEN
        IF NOT EXISTS(SELECT FROM inventory_approval_policy_tier WHERE tenant_id=NEW.tenant_id AND id=NEW.tier_id AND policy_id=NEW.policy_id) THEN
            RAISE EXCEPTION 'tier belongs to another policy' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
