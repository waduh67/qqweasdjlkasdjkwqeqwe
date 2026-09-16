CREATE OR REPLACE FUNCTION warehouse_onu_record_topology() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE transition_at timestamptz;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='INSERT' OR NEW.topology_revision<>OLD.topology_revision THEN
        transition_at:=CASE WHEN TG_OP='INSERT' THEN coalesce(NEW.started_at,NEW.created_at) ELSE clock_timestamp() END;
        IF TG_OP='UPDATE' AND EXISTS(SELECT FROM onu_topology_history WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.id
            AND effective_at>=transition_at) THEN
            RAISE EXCEPTION 'topology clock must advance under the owner row lock' USING ERRCODE='23514';
        END IF;
        INSERT INTO onu_topology_history(tenant_id,onu_id,assignment_id,revision,effective_at,snapshot)
        VALUES(NEW.tenant_id,NEW.id,NEW.assignment_id,NEW.topology_revision,transition_at,
            jsonb_build_object('odpId',NEW.odp_id,'portNumber',NEW.odp_port_number,'installedAt',NEW.installed_at));
    END IF;
    RETURN NEW;
END $$;
