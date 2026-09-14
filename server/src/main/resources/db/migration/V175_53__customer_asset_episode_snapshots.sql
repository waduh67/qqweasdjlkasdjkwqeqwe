CREATE FUNCTION warehouse_assignment_source_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.warehouse_admission='VERIFIED' AND NOT EXISTS (
        SELECT FROM inventory_serialized_asset asset
        JOIN inventory_document_line line ON line.tenant_id=asset.tenant_id AND line.id=asset.origin_document_line_id
        JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
        WHERE asset.tenant_id=NEW.tenant_id AND asset.id=NEW.asset_id AND asset.warehouse_admission='VERIFIED'
            AND asset.legal_owner=NEW.legal_owner AND document.kind=NEW.provenance) THEN
        RAISE EXCEPTION 'assignment title and provenance snapshot must match physical source' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_assignment_source_snapshot BEFORE INSERT ON inventory_asset_assignment
    FOR EACH ROW EXECUTE FUNCTION warehouse_assignment_source_snapshot();

ALTER TABLE onu ADD COLUMN topology_revision bigint NOT NULL DEFAULT 0 CHECK (topology_revision>=0);
CREATE TABLE onu_topology_history (
    tenant_id uuid NOT NULL, onu_id uuid NOT NULL, assignment_id uuid,
    revision bigint NOT NULL CHECK (revision>=0), effective_at timestamptz NOT NULL,
    snapshot jsonb NOT NULL,
    PRIMARY KEY (tenant_id,onu_id,revision),
    FOREIGN KEY (tenant_id,onu_id) REFERENCES onu(tenant_id,id),
    FOREIGN KEY (tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id)
);
ALTER TABLE onu_topology_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE onu_topology_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON onu_topology_history
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
INSERT INTO onu_topology_history(tenant_id,onu_id,assignment_id,revision,effective_at,snapshot)
SELECT tenant_id,id,assignment_id,0,coalesce(started_at,created_at),
    jsonb_build_object('odpId',odp_id,'portNumber',odp_port_number,'installedAt',installed_at) FROM onu;
CREATE TRIGGER warehouse_topology_append_only BEFORE UPDATE OR DELETE ON onu_topology_history
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON onu_topology_history TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_onu_topology_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='INSERT' THEN
        IF NEW.topology_revision<>0 THEN RAISE EXCEPTION 'topology begins at revision zero' USING ERRCODE='23514'; END IF;
    ELSE
        IF NEW.topology_revision<>OLD.topology_revision THEN
            RAISE EXCEPTION 'topology revision is owner maintained' USING ERRCODE='23514';
        END IF;
        IF (NEW.odp_id,NEW.odp_port_number,NEW.installed_at) IS DISTINCT FROM (OLD.odp_id,OLD.odp_port_number,OLD.installed_at) THEN
            NEW.topology_revision:=OLD.topology_revision+1;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_onu_topology_revision BEFORE INSERT OR UPDATE ON onu
    FOR EACH ROW EXECUTE FUNCTION warehouse_onu_topology_revision();

CREATE FUNCTION warehouse_onu_record_topology() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='INSERT' OR NEW.topology_revision<>OLD.topology_revision THEN
        INSERT INTO onu_topology_history(tenant_id,onu_id,assignment_id,revision,effective_at,snapshot)
        VALUES (NEW.tenant_id,NEW.id,NEW.assignment_id,NEW.topology_revision,
            CASE WHEN TG_OP='INSERT' THEN coalesce(NEW.started_at,NEW.created_at) ELSE transaction_timestamp() END,
            jsonb_build_object('odpId',NEW.odp_id,'portNumber',NEW.odp_port_number,'installedAt',NEW.installed_at));
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_onu_record_topology AFTER INSERT OR UPDATE ON onu
    FOR EACH ROW EXECUTE FUNCTION warehouse_onu_record_topology();

CREATE FUNCTION warehouse_topology_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS (SELECT FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id AND topology_revision=NEW.revision
        AND assignment_id IS NOT DISTINCT FROM NEW.assignment_id
        AND NEW.snapshot=jsonb_build_object('odpId',odp_id,'portNumber',odp_port_number,'installedAt',installed_at)) THEN
        RAISE EXCEPTION 'topology snapshot requires exact owner revision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_topology_snapshot BEFORE INSERT ON onu_topology_history
    FOR EACH ROW EXECUTE FUNCTION warehouse_topology_snapshot_guard();
