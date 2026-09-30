CREATE FUNCTION fulfillment_legacy_cutoff_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE data jsonb; scope uuid; stage text; frozen boolean; checkpoint fulfillment_checkpoint;
BEGIN
    data:=CASE WHEN TG_OP='INSERT' THEN to_jsonb(NEW) ELSE to_jsonb(OLD) END;
    scope:=(data->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT state INTO stage FROM inventory_tenant_cutover WHERE tenant_id=scope FOR SHARE;
    IF TG_TABLE_NAME='fulfillment_checkpoint' THEN
        IF data->>'source'<>'WORK_ORDER' THEN RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END; END IF;
        SELECT EXISTS(SELECT FROM fulfillment_approval_snapshot WHERE tenant_id=scope
            AND namespace=data->>'namespace' AND operation_key=data->>'operation_key'
            AND payload_hash=data->>'canonical_hash') INTO frozen;
        IF frozen THEN RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END; END IF;
        -- claimOrCreate uses INSERT ON CONFLICT even for historical terminal replay.
        IF TG_OP='INSERT' AND EXISTS(SELECT FROM fulfillment_checkpoint WHERE tenant_id=scope
            AND namespace=NEW.namespace AND operation_key=NEW.operation_key) THEN RETURN NEW; END IF;
        IF stage IS DISTINCT FROM 'LEGACY' THEN
            IF TG_OP<>'UPDATE' THEN
                RAISE EXCEPTION 'legacy fulfillment creation or removal is closed after cutoff' USING ERRCODE='23514';
            END IF;
            IF (to_jsonb(NEW)-ARRAY['state','last_effect','attempts','outcome','checkpoint_updated_at','updated_at']) IS DISTINCT FROM
                (to_jsonb(OLD)-ARRAY['state','last_effect','attempts','outcome','checkpoint_updated_at','updated_at']) THEN
                RAISE EXCEPTION 'legacy fulfillment business identity is fixed at cutoff' USING ERRCODE='23514';
            END IF;
        END IF;
        IF TG_OP='UPDATE' AND OLD.state IN ('APPLIED','FAILED_PERMANENT','MANUAL_RESOLVED') AND
            (NEW.state,NEW.last_effect,NEW.outcome) IS DISTINCT FROM (OLD.state,OLD.last_effect,OLD.outcome) THEN
            RAISE EXCEPTION 'legacy terminal outcome is immutable' USING ERRCODE='23514';
        END IF;
    ELSE
        -- Progress/new delivery follows the same checkpoint lock as the owner.
        -- ACK only locks its outbox row and cannot wait in the opposite order.
        IF TG_TABLE_NAME='fulfillment_effect_progress' OR TG_OP='INSERT' THEN
            SELECT * INTO checkpoint FROM fulfillment_checkpoint WHERE tenant_id=scope AND id=(data->>'fulfillment_id')::uuid FOR UPDATE;
        ELSE
            SELECT * INTO checkpoint FROM fulfillment_checkpoint WHERE tenant_id=scope AND id=(data->>'fulfillment_id')::uuid;
        END IF;
        IF checkpoint.id IS NOT NULL AND checkpoint.source<>'WORK_ORDER' THEN RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END; END IF;
        SELECT EXISTS(SELECT FROM fulfillment_approval_snapshot WHERE tenant_id=scope
            AND namespace=checkpoint.namespace AND operation_key=checkpoint.operation_key AND payload_hash=checkpoint.canonical_hash) INTO frozen;
        IF NOT frozen AND stage IS DISTINCT FROM 'LEGACY' THEN
            IF TG_TABLE_NAME='fulfillment_effect_progress' OR TG_OP<>'UPDATE' THEN
                RAISE EXCEPTION 'legacy effect or delivery creation is closed after cutoff' USING ERRCODE='23514';
            END IF;
            IF (to_jsonb(NEW)-ARRAY['published_at','claimed_by','lease_until','attempts']) IS DISTINCT FROM
                (to_jsonb(OLD)-ARRAY['published_at','claimed_by','lease_until','attempts']) THEN
                RAISE EXCEPTION 'legacy delivery identity is fixed at cutoff' USING ERRCODE='23514';
            END IF;
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER fulfillment_legacy_cutoff BEFORE INSERT OR UPDATE OR DELETE ON fulfillment_checkpoint
    FOR EACH ROW EXECUTE FUNCTION fulfillment_legacy_cutoff_guard();
CREATE TRIGGER fulfillment_legacy_cutoff BEFORE INSERT OR UPDATE OR DELETE ON fulfillment_outbox
    FOR EACH ROW EXECUTE FUNCTION fulfillment_legacy_cutoff_guard();
CREATE TRIGGER fulfillment_legacy_cutoff BEFORE INSERT OR UPDATE OR DELETE ON fulfillment_effect_progress
    FOR EACH ROW EXECUTE FUNCTION fulfillment_legacy_cutoff_guard();

CREATE TRIGGER warehouse_tombstone_history BEFORE UPDATE OR DELETE ON inventory_serial_tombstone
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE INSERT,UPDATE,DELETE ON inventory_serial_tombstone FROM warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_migration_review_closed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_lock_migration_history(NEW.tenant_id,NEW.batch_id);
    IF EXISTS(SELECT FROM inventory_migration_admission WHERE tenant_id=NEW.tenant_id AND batch_id=NEW.batch_id) THEN
        RAISE EXCEPTION 'approved migration review is permanently sealed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_migration_review_closed BEFORE INSERT ON inventory_migration_evidence
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_review_closed();
CREATE TRIGGER warehouse_migration_review_closed BEFORE INSERT ON inventory_migration_resolution
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_review_closed();
