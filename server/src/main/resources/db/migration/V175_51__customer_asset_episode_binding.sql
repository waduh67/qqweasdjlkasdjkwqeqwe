CREATE FUNCTION warehouse_episode_asset_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF current_setting('transaction_isolation') NOT IN ('read committed','read uncommitted') THEN
        RAISE EXCEPTION 'episode mutations require read committed' USING ERRCODE='40001';
    END IF;
    target:=CASE WHEN TG_TABLE_NAME='inventory_identity_claim' THEN (to_jsonb(NEW)->>'admitted_asset_id')::uuid ELSE (to_jsonb(NEW)->>'asset_id')::uuid END;
    IF target IS NOT NULL THEN
        PERFORM id FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=target FOR NO KEY UPDATE;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_assignment_asset_lock BEFORE INSERT OR UPDATE ON inventory_asset_assignment
    FOR EACH ROW EXECUTE FUNCTION warehouse_episode_asset_lock();
CREATE TRIGGER warehouse_onu_asset_lock BEFORE INSERT OR UPDATE ON onu
    FOR EACH ROW EXECUTE FUNCTION warehouse_episode_asset_lock();
CREATE TRIGGER warehouse_claim_episode_lock BEFORE INSERT OR UPDATE ON inventory_identity_claim
    FOR EACH ROW EXECUTE FUNCTION warehouse_episode_asset_lock();

CREATE FUNCTION warehouse_assert_asset_episodes(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE assignment inventory_asset_assignment; asset inventory_serialized_asset;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=target;
    FOR assignment IN SELECT * FROM inventory_asset_assignment WHERE tenant_id=scope AND asset_id=target AND warehouse_admission='VERIFIED' LOOP
        IF asset.id IS NULL OR asset.warehouse_admission<>'VERIFIED' THEN
            RAISE EXCEPTION 'assignment requires admitted physical asset' USING ERRCODE='23514';
        END IF;
        IF EXISTS (SELECT FROM inventory_asset_assignment other WHERE other.tenant_id=scope AND other.asset_id=target
            AND other.id<>assignment.id AND other.warehouse_admission='VERIFIED'
            AND tstzrange(other.started_at,other.ended_at,'[)') && tstzrange(assignment.started_at,assignment.ended_at,'[)')) THEN
            RAISE EXCEPTION 'physical asset assignment intervals overlap' USING ERRCODE='23514';
        END IF;
        IF assignment.ended_at IS NULL THEN
            IF asset.condition<>'SERVICEABLE' OR asset.status IN ('QUARANTINE','LOST','DISPOSED','RETURNED')
                OR NOT EXISTS (SELECT FROM inventory_identity_claim WHERE tenant_id=scope AND identity_type='SERIAL'
                    AND canonical_value=asset.canonical_serial AND state='ADMITTED' AND admitted_asset_id=target)
                OR (asset.canonical_mac IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_identity_claim WHERE tenant_id=scope
                    AND identity_type='MAC' AND canonical_value=asset.canonical_mac AND state='ADMITTED' AND admitted_asset_id=target))
                OR EXISTS (SELECT FROM inventory_segment WHERE tenant_id=scope AND asset_id=target AND state<>'ACTIVE')
                OR EXISTS (SELECT FROM onu WHERE tenant_id=scope AND warehouse_admission='LEGACY_UNRESOLVED'
                    AND canonical_serial_candidate=asset.canonical_serial AND retired_at IS NULL) THEN
                RAISE EXCEPTION 'active assignment requires serviceable asset and resolved identity claims' USING ERRCODE='23514';
            END IF;
        END IF;
        IF assignment.issue_line_id IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_issue_line
            WHERE tenant_id=scope AND id=assignment.issue_line_id AND stock_identity_id=target) THEN
            RAISE EXCEPTION 'assignment issue line must name exact physical asset' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF EXISTS (SELECT FROM onu episode LEFT JOIN inventory_asset_assignment assignment
        ON assignment.tenant_id=episode.tenant_id AND assignment.id=episode.assignment_id
        WHERE episode.tenant_id=scope AND episode.asset_id=target AND episode.warehouse_admission='VERIFIED'
        AND (assignment.id IS NULL OR assignment.warehouse_admission<>'VERIFIED' OR assignment.asset_id<>episode.asset_id
            OR assignment.customer_id<>episode.customer_id OR episode.original_customer_id<>episode.customer_id
            OR assignment.started_at IS DISTINCT FROM episode.started_at OR assignment.ended_at IS DISTINCT FROM episode.retired_at
            OR episode.canonical_serial IS DISTINCT FROM asset.canonical_serial OR episode.provenance<>assignment.provenance)) THEN
        RAISE EXCEPTION 'ONU episode requires exact assignment interval identity and customer' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT FROM onu first JOIN onu second ON second.tenant_id=first.tenant_id AND second.asset_id=first.asset_id
        AND second.id<>first.id AND second.warehouse_admission='VERIFIED'
        WHERE first.tenant_id=scope AND first.asset_id=target AND first.warehouse_admission='VERIFIED'
        AND tstzrange(first.started_at,first.retired_at,'[)') && tstzrange(second.started_at,second.retired_at,'[)')) THEN
        RAISE EXCEPTION 'ONU episode intervals overlap' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_asset_episode_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    target:=CASE TG_TABLE_NAME
        WHEN 'inventory_serialized_asset' THEN NEW.id
        WHEN 'inventory_identity_claim' THEN (to_jsonb(NEW)->>'admitted_asset_id')::uuid
        ELSE (to_jsonb(NEW)->>'asset_id')::uuid END;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_asset_episodes(NEW.tenant_id,target); END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_assignment','onu','inventory_serialized_asset','inventory_identity_claim','inventory_segment'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_asset_episode_final AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_asset_episode_final_guard()',table_name);
    END LOOP;
END $$;
