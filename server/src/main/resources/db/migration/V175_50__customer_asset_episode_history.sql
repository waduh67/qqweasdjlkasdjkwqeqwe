CREATE FUNCTION warehouse_assignment_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='UPDATE' THEN
        IF (to_jsonb(NEW)-ARRAY['ended_at','revision']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['ended_at','revision'])
            OR OLD.ended_at IS NOT NULL OR NEW.ended_at IS NULL OR NEW.revision<>OLD.revision+1 THEN
            RAISE EXCEPTION 'assignment history permits only one revisioned closure' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.revision<>0 THEN
        RAISE EXCEPTION 'assignment begins at revision zero' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_assignment_history BEFORE INSERT OR UPDATE ON inventory_asset_assignment
    FOR EACH ROW EXECUTE FUNCTION warehouse_assignment_history_guard();

CREATE FUNCTION warehouse_authorization_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='INSERT' THEN
        IF NEW.consumed OR NEW.revision<>0 THEN
            RAISE EXCEPTION 'authorization must begin unconsumed' USING ERRCODE='23514';
        END IF;
    ELSIF (to_jsonb(NEW)-ARRAY['consumed','consumed_at','revision']) IS DISTINCT FROM
        (to_jsonb(OLD)-ARRAY['consumed','consumed_at','revision']) OR OLD.consumed OR NOT NEW.consumed
        OR NEW.revision<>OLD.revision+1 THEN
        RAISE EXCEPTION 'authorization binding is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_authorization_history BEFORE INSERT OR UPDATE ON inventory_deployment_authorization
    FOR EACH ROW EXECUTE FUNCTION warehouse_authorization_history_guard();

CREATE FUNCTION warehouse_assignment_record_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    INSERT INTO inventory_asset_assignment_history(tenant_id,assignment_id,revision,snapshot)
        VALUES (NEW.tenant_id,NEW.id,NEW.revision,to_jsonb(NEW));
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_assignment_record AFTER INSERT OR UPDATE ON inventory_asset_assignment
    FOR EACH ROW EXECUTE FUNCTION warehouse_assignment_record_history();

CREATE FUNCTION warehouse_authorization_record_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    INSERT INTO inventory_deployment_authorization_history(tenant_id,authorization_id,revision,snapshot)
        VALUES (NEW.tenant_id,NEW.id,NEW.revision,to_jsonb(NEW));
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_authorization_record AFTER INSERT OR UPDATE ON inventory_deployment_authorization
    FOR EACH ROW EXECUTE FUNCTION warehouse_authorization_record_history();

CREATE FUNCTION warehouse_episode_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_asset_assignment_history' THEN
        SELECT to_jsonb(assignment) INTO expected FROM inventory_asset_assignment assignment
            WHERE tenant_id=NEW.tenant_id AND id=NEW.assignment_id AND revision=NEW.revision;
    ELSE
        SELECT to_jsonb(permit) INTO expected FROM inventory_deployment_authorization permit
            WHERE tenant_id=NEW.tenant_id AND id=NEW.authorization_id AND revision=NEW.revision;
    END IF;
    IF expected IS NULL OR NEW.snapshot IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'history snapshot must match owner row revision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_assignment_snapshot BEFORE INSERT ON inventory_asset_assignment_history
    FOR EACH ROW EXECUTE FUNCTION warehouse_episode_snapshot_guard();
CREATE TRIGGER warehouse_authorization_snapshot BEFORE INSERT ON inventory_deployment_authorization_history
    FOR EACH ROW EXECUTE FUNCTION warehouse_episode_snapshot_guard();

CREATE FUNCTION warehouse_onu_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='INSERT' THEN
        NEW.original_customer_id:=NEW.customer_id;
        NEW.original_topology:=jsonb_build_object('odpId',NEW.odp_id,'portNumber',NEW.odp_port_number,'installedAt',NEW.installed_at);
        IF NEW.episode_revision<>0 THEN
            RAISE EXCEPTION 'ONU episode begins at revision zero' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (NEW.id,NEW.tenant_id,NEW.customer_id,NEW.serial_number,NEW.original_customer_id,NEW.original_topology,NEW.created_at)
            IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.customer_id,OLD.serial_number,OLD.original_customer_id,OLD.original_topology,OLD.created_at) THEN
            RAISE EXCEPTION 'ONU original customer and identity history is immutable' USING ERRCODE='23514';
        END IF;
        IF OLD.warehouse_admission='VERIFIED' AND
            (NEW.asset_id,NEW.assignment_id,NEW.canonical_serial,NEW.started_at,NEW.provenance)
            IS DISTINCT FROM (OLD.asset_id,OLD.assignment_id,OLD.canonical_serial,OLD.started_at,OLD.provenance) THEN
            RAISE EXCEPTION 'ONU verified deployment cannot be reparented' USING ERRCODE='23514';
        END IF;
        IF OLD.retired_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'retired ONU episode is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.retired_at IS DISTINCT FROM OLD.retired_at AND NEW.episode_revision<>OLD.episode_revision+1 THEN
            RAISE EXCEPTION 'ONU closure requires next revision' USING ERRCODE='23514';
        END IF;
        IF OLD.warehouse_admission='LEGACY_UNRESOLVED' AND current_user<>
            pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid=TG_RELID)) AND
            (NEW.asset_id,NEW.assignment_id,NEW.canonical_serial,NEW.started_at,NEW.provenance,NEW.canonical_serial_candidate)
            IS DISTINCT FROM (OLD.asset_id,OLD.assignment_id,OLD.canonical_serial,OLD.started_at,OLD.provenance,OLD.canonical_serial_candidate) THEN
            RAISE EXCEPTION 'legacy episode links require reconciliation owner' USING ERRCODE='42501';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_onu_history BEFORE INSERT OR UPDATE ON onu FOR EACH ROW EXECUTE FUNCTION warehouse_onu_history_guard();

CREATE FUNCTION warehouse_handover_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.warehouse_admission='VERIFIED' AND NOT EXISTS
        (SELECT FROM inventory_asset_assignment assignment WHERE assignment.tenant_id=NEW.tenant_id AND assignment.id=NEW.assignment_id
            AND assignment.warehouse_admission='VERIFIED' AND assignment.asset_id=NEW.asset_id
            AND assignment.work_order_id=NEW.work_order_id AND assignment.customer_id=NEW.customer_id
            AND assignment.ownership_mode=NEW.ownership_mode AND assignment.revision=NEW.assignment_revision
            AND NEW.accepted_at>=assignment.started_at AND assignment.ended_at IS NULL) THEN
        RAISE EXCEPTION 'handover requires exact active assignment binding' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_handover_binding BEFORE INSERT ON inventory_asset_handover
    FOR EACH ROW EXECUTE FUNCTION warehouse_handover_binding_guard();
