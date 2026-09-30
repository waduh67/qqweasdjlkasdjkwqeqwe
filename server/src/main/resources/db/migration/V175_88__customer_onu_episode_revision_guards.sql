CREATE FUNCTION warehouse_assert_onu_episode_revision(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE episode onu; event customer_onu_episode_event; previous_event customer_onu_episode_event;
    installation customer_asset_installation; retirement customer_asset_retirement; topology onu_topology_history;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO episode FROM onu WHERE tenant_id=scope AND id=target;
    IF NOT FOUND THEN RAISE EXCEPTION 'ONU_EPISODE_HISTORY_REQUIRED' USING ERRCODE='23514'; END IF;
    IF episode.warehouse_admission<>'VERIFIED' THEN RETURN; END IF;
    FOR event IN SELECT * FROM customer_onu_episode_event WHERE tenant_id=scope AND onu_id=target ORDER BY revision LOOP
        IF (event.assignment_id,event.asset_id,event.customer_id) IS DISTINCT FROM (episode.assignment_id,episode.asset_id,episode.customer_id)
            OR NOT EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=scope AND id=event.assignment_id
                AND asset_id=event.asset_id AND customer_id=event.customer_id) THEN
            RAISE EXCEPTION 'ONU_EPISODE_EVENT_IDENTITY' USING ERRCODE='23514'; END IF;
        SELECT * INTO topology FROM onu_topology_history WHERE tenant_id=scope AND onu_id=target AND revision=event.topology_revision;
        IF topology.onu_id IS NULL OR topology.assignment_id IS DISTINCT FROM event.assignment_id THEN
            RAISE EXCEPTION 'ONU_EPISODE_TOPOLOGY_EVENT_REQUIRED' USING ERRCODE='23514'; END IF;
        IF previous_event.onu_id IS NULL THEN
            IF NOT event.is_baseline OR event.source_revision IS NOT NULL
                OR event.revision<>(CASE WHEN event.kind='OPENED' THEN 0 ELSE 1 END) OR event.kind='TOPOLOGY' THEN
                RAISE EXCEPTION 'ONU_EPISODE_BASELINE_REQUIRED' USING ERRCODE='23514'; END IF;
        ELSE
            IF event.is_baseline OR previous_event.kind='RETIRED' OR event.source_revision<>previous_event.revision
                OR event.revision<>previous_event.revision+1
                OR (event.kind='TOPOLOGY' AND event.topology_revision<>previous_event.topology_revision+1)
                OR (event.kind='RETIRED' AND event.topology_revision NOT IN (previous_event.topology_revision,previous_event.topology_revision+1)) THEN
                RAISE EXCEPTION 'ONU_EPISODE_EVENT_SEQUENCE' USING ERRCODE='23514'; END IF;
        END IF;
        previous_event:=event;
    END LOOP;
    IF previous_event.onu_id IS NULL OR episode.episode_revision<>previous_event.revision
        OR episode.topology_revision<>previous_event.topology_revision OR episode.retired_at IS DISTINCT FROM previous_event.retired_at
        OR topology.snapshot IS DISTINCT FROM jsonb_build_object('odpId',episode.odp_id,'portNumber',episode.odp_port_number,'installedAt',episode.installed_at) THEN
        RAISE EXCEPTION 'ONU_EPISODE_REVISION_EVENT_MISMATCH' USING ERRCODE='23514'; END IF;
    SELECT * INTO installation FROM customer_asset_installation WHERE tenant_id=scope AND onu_id=target;
    SELECT * INTO retirement FROM customer_asset_retirement WHERE tenant_id=scope AND onu_id=target;
    IF installation.id IS NOT NULL AND episode.retired_at IS NOT NULL THEN
        IF retirement.removal_id IS NULL OR retirement.removal_id IS DISTINCT FROM previous_event.removal_id
            OR retirement.episode_id<>installation.id OR retirement.retired_at<>episode.retired_at
            OR (retirement.response::jsonb->>'episodeRevision')::bigint IS DISTINCT FROM episode.episode_revision
            OR (retirement.response::jsonb->>'onuId')::uuid IS DISTINCT FROM episode.id
            OR (retirement.response::jsonb->>'assignmentId')::uuid IS DISTINCT FROM episode.assignment_id
            OR (retirement.response::jsonb->>'assetId')::uuid IS DISTINCT FROM episode.asset_id
            OR (retirement.response::jsonb->>'customerId')::uuid IS DISTINCT FROM episode.customer_id
            OR NOT EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND id=retirement.removal_id
                AND assignment_id=episode.assignment_id AND asset_id=episode.asset_id AND customer_id=episode.customer_id
                AND removed_at=episode.retired_at) THEN
            RAISE EXCEPTION 'ONU_RETIREMENT_REVISION_BINDING' USING ERRCODE='23514'; END IF;
    ELSIF retirement.removal_id IS NOT NULL THEN
        RAISE EXCEPTION 'ONU_RETIREMENT_REQUIRES_CLOSED_EPISODE' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_onu_episode_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.warehouse_admission<>'VERIFIED' THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' OR OLD.warehouse_admission<>'VERIFIED' THEN
        IF NEW.episode_revision<>0 THEN RAISE EXCEPTION 'ONU_EPISODE_OPENING_REVISION' USING ERRCODE='23514'; END IF;
    ELSE
        PERFORM warehouse_assert_onu_episode_revision(OLD.tenant_id,OLD.id);
        IF NEW.retired_at IS DISTINCT FROM OLD.retired_at OR
            (NEW.odp_id,NEW.odp_port_number,NEW.installed_at) IS DISTINCT FROM (OLD.odp_id,OLD.odp_port_number,OLD.installed_at) THEN
            IF OLD.retired_at IS NOT NULL OR NEW.episode_revision NOT IN (OLD.episode_revision,OLD.episode_revision+1) THEN
                RAISE EXCEPTION 'ONU_EPISODE_EVENT_REVISION_INVALID' USING ERRCODE='23514'; END IF;
            NEW.episode_revision:=OLD.episode_revision+1;
        ELSIF NEW.episode_revision<>OLD.episode_revision THEN
            RAISE EXCEPTION 'ONU_EPISODE_REVISION_REQUIRES_EVENT' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_onu_episode_revision BEFORE INSERT OR UPDATE ON onu FOR EACH ROW EXECUTE FUNCTION warehouse_onu_episode_revision_guard();

CREATE FUNCTION warehouse_record_onu_episode_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE opening boolean; removal uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.warehouse_admission<>'VERIFIED' THEN RETURN NEW; END IF;
    opening:=TG_OP='INSERT' OR OLD.warehouse_admission<>'VERIFIED';
    IF NOT opening AND NEW.episode_revision=OLD.episode_revision THEN RETURN NEW; END IF;
    IF NEW.retired_at IS NOT NULL THEN
        SELECT id INTO removal FROM inventory_asset_removal WHERE tenant_id=NEW.tenant_id AND assignment_id=NEW.assignment_id;
    END IF;
    INSERT INTO customer_onu_episode_event(tenant_id,onu_id,revision,source_revision,is_baseline,kind,assignment_id,asset_id,customer_id,
        topology_revision,retired_at,removal_id)
    VALUES(NEW.tenant_id,NEW.id,NEW.episode_revision,CASE WHEN opening THEN NULL ELSE OLD.episode_revision END,opening,
        CASE WHEN opening THEN 'OPENED' WHEN NEW.retired_at IS NOT NULL THEN 'RETIRED' ELSE 'TOPOLOGY' END,
        NEW.assignment_id,NEW.asset_id,NEW.customer_id,NEW.topology_revision,NEW.retired_at,removal);
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_zz_onu_episode_event AFTER INSERT OR UPDATE ON onu FOR EACH ROW EXECUTE FUNCTION warehouse_record_onu_episode_event();

CREATE FUNCTION warehouse_onu_episode_event_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE episode onu; prior customer_onu_episode_event;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO episode FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id;
    SELECT * INTO prior FROM customer_onu_episode_event WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.onu_id ORDER BY revision DESC LIMIT 1;
    IF NEW.created_xid<>pg_current_xact_id() OR episode.id IS NULL OR episode.warehouse_admission<>'VERIFIED'
        OR (NEW.assignment_id,NEW.asset_id,NEW.customer_id,NEW.revision,NEW.topology_revision)
            IS DISTINCT FROM (episode.assignment_id,episode.asset_id,episode.customer_id,episode.episode_revision,episode.topology_revision)
        OR NEW.retired_at IS DISTINCT FROM episode.retired_at
        OR (NEW.is_baseline AND (prior.onu_id IS NOT NULL OR NEW.kind<>'OPENED' OR NEW.revision<>0 OR NEW.topology_revision<>0))
        OR (NOT NEW.is_baseline AND (prior.onu_id IS NULL OR NEW.source_revision<>prior.revision OR NEW.revision<>prior.revision+1
            OR prior.kind='RETIRED' OR (NEW.kind='TOPOLOGY' AND NEW.topology_revision<>prior.topology_revision+1))) THEN
        RAISE EXCEPTION 'ONU_EPISODE_EVENT_REQUIRES_OWNER_TRANSITION' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_onu_episode_event_insert BEFORE INSERT ON customer_onu_episode_event
    FOR EACH ROW EXECUTE FUNCTION warehouse_onu_episode_event_insert_guard();

CREATE FUNCTION warehouse_onu_episode_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; target uuid; assignment uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    FOR row_data IN SELECT value FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) LOOP
        scope:=(row_data->>'tenant_id')::uuid;
        target:=CASE WHEN TG_TABLE_NAME='onu' THEN (row_data->>'id')::uuid ELSE (row_data->>'onu_id')::uuid END;
        IF target IS NOT NULL THEN PERFORM warehouse_assert_onu_episode_revision(scope,target); END IF;
        assignment:=CASE WHEN TG_TABLE_NAME='inventory_asset_assignment' THEN (row_data->>'id')::uuid ELSE (row_data->>'assignment_id')::uuid END;
        IF target IS NULL AND assignment IS NOT NULL THEN
            FOR target IN SELECT id FROM onu WHERE tenant_id=scope AND assignment_id=assignment LOOP
                PERFORM warehouse_assert_onu_episode_revision(scope,target);
            END LOOP;
        END IF;
    END LOOP;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text; definition text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['onu','onu_topology_history','customer_onu_episode_event','customer_asset_retirement',
        'inventory_asset_removal','inventory_asset_assignment','customer_asset_installation','customer_asset_relocation'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_onu_episode_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_onu_episode_final_guard()',table_name);
    END LOOP;
    definition:=pg_get_functiondef('warehouse_assert_asset_episodes(uuid,uuid)'::regprocedure);
    IF strpos(definition,'DECLARE assignment inventory_asset_assignment; asset inventory_serialized_asset;')=0 THEN
        RAISE EXCEPTION 'episode validator declarations changed'; END IF;
    definition:=replace(definition,'DECLARE assignment inventory_asset_assignment; asset inventory_serialized_asset;',
        'DECLARE assignment inventory_asset_assignment; asset inventory_serialized_asset; episode_id uuid;');
    EXECUTE replace(definition,'    SELECT * INTO asset FROM inventory_serialized_asset',$body$
    FOR episode_id IN SELECT id FROM onu WHERE tenant_id=scope AND asset_id=target LOOP
        PERFORM warehouse_assert_onu_episode_revision(scope,episode_id);
    END LOOP;
    SELECT * INTO asset FROM inventory_serialized_asset$body$);
    definition:=pg_get_functiondef('warehouse_assert_asset_removal(uuid,uuid)'::regprocedure);
    IF strpos(definition,'body:=removal.result::jsonb;')=0 THEN RAISE EXCEPTION 'removal validator entry changed'; END IF;
    EXECUTE replace(definition,'body:=removal.result::jsonb;',$body$
    PERFORM warehouse_assert_onu_episode_revision(scope,tracked.onu_id) FROM customer_asset_installation tracked
        WHERE tracked.tenant_id=scope AND tracked.assignment_id IN (removal.assignment_id,removal.replacement_assignment_id) AND tracked.onu_id IS NOT NULL;
    body:=removal.result::jsonb;$body$);
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,'IF outcome.creates_onu AND NOT EXISTS')=0 THEN RAISE EXCEPTION 'deployment episode validation entry changed'; END IF;
    EXECUTE replace(definition,'IF outcome.creates_onu AND NOT EXISTS',$body$
    IF outcome.creates_onu THEN PERFORM warehouse_assert_onu_episode_revision(scope,episode.onu_id); END IF;
    IF outcome.creates_onu AND NOT EXISTS$body$);
    definition:=pg_get_functiondef('warehouse_relocation_final_guard()'::regprocedure);
    IF strpos(definition,'    RETURN NULL;')=0 THEN RAISE EXCEPTION 'relocation validator entry changed'; END IF;
    EXECUTE replace(definition,'    RETURN NULL;',$body$
    IF NEW.response::jsonb ? 'episodeRevision' AND NOT EXISTS(SELECT FROM customer_onu_episode_event
        WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.onu_id AND revision=(NEW.response::jsonb->>'episodeRevision')::bigint
        AND topology_revision=NEW.target_revision AND assignment_id=NEW.assignment_id AND customer_id=NEW.customer_id AND kind='TOPOLOGY') THEN
        RAISE EXCEPTION 'ONU_RELOCATION_EPISODE_REVISION' USING ERRCODE='23514'; END IF;
    RETURN NULL;$body$);
END $$;
