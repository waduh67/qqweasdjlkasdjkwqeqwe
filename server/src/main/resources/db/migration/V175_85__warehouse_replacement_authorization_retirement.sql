CREATE TABLE inventory_deployment_retirement (
    tenant_id uuid NOT NULL, authorization_id uuid NOT NULL, removal_id uuid NOT NULL, asset_snapshot jsonb NOT NULL,
    PRIMARY KEY(tenant_id,authorization_id),
    FOREIGN KEY(tenant_id,authorization_id) REFERENCES inventory_deployment_authorization(tenant_id,id),
    FOREIGN KEY(tenant_id,removal_id) REFERENCES inventory_asset_removal(tenant_id,id)
);
ALTER TABLE inventory_deployment_retirement ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_deployment_retirement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_deployment_retirement USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_deployment_retirement FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION warehouse_retire_competing_authorizations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    INSERT INTO inventory_deployment_retirement(tenant_id,authorization_id,removal_id,asset_snapshot)
        SELECT permit.tenant_id,permit.id,NEW.id,to_jsonb(asset) FROM inventory_deployment_authorization permit
        JOIN inventory_serialized_asset asset ON asset.tenant_id=permit.tenant_id AND asset.id=permit.asset_id
        WHERE permit.tenant_id=NEW.tenant_id AND permit.previous_assignment_id=NEW.assignment_id AND NOT permit.consumed;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_removal_retire_authorizations AFTER INSERT ON inventory_asset_removal
    FOR EACH ROW EXECUTE FUNCTION warehouse_retire_competing_authorizations();
CREATE FUNCTION warehouse_authorization_retirement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS(SELECT FROM inventory_asset_removal removal JOIN inventory_deployment_authorization permit
        ON permit.tenant_id=removal.tenant_id AND permit.previous_assignment_id=removal.assignment_id
        JOIN inventory_serialized_asset asset ON asset.tenant_id=permit.tenant_id AND asset.id=permit.asset_id
        WHERE removal.tenant_id=NEW.tenant_id AND removal.id=NEW.removal_id AND removal.created_xid=pg_current_xact_id()
        AND permit.id=NEW.authorization_id AND NOT permit.consumed AND permit.purpose='REPLACE' AND NEW.asset_snapshot=to_jsonb(asset)) THEN
        RAISE EXCEPTION 'AUTHORIZATION_RETIREMENT_REMOVAL_REQUIRED' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_authorization_retirement BEFORE INSERT ON inventory_deployment_retirement
    FOR EACH ROW EXECUTE FUNCTION warehouse_authorization_retirement_guard();
DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    IF strpos(definition,'IF permit.consumed AND EXISTS(SELECT FROM inventory_asset_removal')=0
        OR strpos(definition,'IF NOT permit.consumed AND permit.purpose IN (''REPLACE'',''REMOVE'')')=0 THEN
        RAISE EXCEPTION 'authorization lifecycle clauses missing'; END IF;
    definition:=replace(definition,'IF permit.consumed AND EXISTS(SELECT FROM inventory_asset_removal',$body$
    IF EXISTS(SELECT FROM inventory_deployment_retirement WHERE tenant_id=scope AND inventory_deployment_retirement.authorization_id=permit.id) THEN
        IF permit.consumed THEN RAISE EXCEPTION 'RETIRED_AUTHORIZATION_CANNOT_BE_CONSUMED' USING ERRCODE='23514'; END IF;
        SELECT (jsonb_populate_record(NULL::inventory_serialized_asset,retirement.asset_snapshot)).* INTO physical
            FROM inventory_deployment_retirement retirement WHERE tenant_id=scope AND retirement.authorization_id=permit.id;
    END IF;
    IF permit.consumed AND EXISTS(SELECT FROM inventory_asset_removal$body$);
    definition:=replace(definition,'IF NOT permit.consumed AND permit.purpose IN (''REPLACE'',''REMOVE'')',
        'IF NOT permit.consumed AND permit.purpose IN (''REPLACE'',''REMOVE'') AND NOT EXISTS(SELECT FROM inventory_deployment_retirement WHERE tenant_id=scope AND inventory_deployment_retirement.authorization_id=permit.id)');
    EXECUTE definition;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_deployment_retirement TO warehouse_app;
    END IF;
END $$;
