ALTER TABLE tenant ADD COLUMN warehouse_created_xid xid8;

CREATE FUNCTION warehouse_tenant_creation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        NEW.warehouse_created_xid:=pg_current_xact_id();
    ELSIF NEW.warehouse_created_xid IS DISTINCT FROM OLD.warehouse_created_xid THEN
        RAISE EXCEPTION 'tenant creation transaction is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_tenant_creation BEFORE INSERT OR UPDATE ON tenant
    FOR EACH ROW EXECUTE FUNCTION warehouse_tenant_creation_guard();

CREATE TABLE inventory_reference_bootstrap (
    tenant_id uuid PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    resulting_epoch bigint NOT NULL CHECK(resulting_epoch=1),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);
ALTER TABLE inventory_reference_bootstrap ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_reference_bootstrap FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_reference_bootstrap
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_bootstrap_no_update BEFORE UPDATE ON inventory_reference_bootstrap
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER warehouse_bootstrap_no_delete BEFORE DELETE ON inventory_reference_bootstrap
    FOR EACH ROW EXECUTE FUNCTION warehouse_tenant_control_delete_guard();

CREATE FUNCTION warehouse_reference_bootstrap_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.created_xid<>pg_current_xact_id() OR NEW.created_at<>transaction_timestamp() OR
        NOT EXISTS(SELECT FROM tenant WHERE id=NEW.tenant_id
            AND warehouse_created_xid=pg_current_xact_id() AND created_at>warehouse_activation_at()) OR
        EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id) OR
        EXISTS(SELECT FROM work_order WHERE tenant_id=NEW.tenant_id) OR
        EXISTS(SELECT FROM fulfillment_checkpoint WHERE tenant_id=NEW.tenant_id) THEN
        RAISE EXCEPTION 'reference bootstrap requires a newly inserted empty tenant; existing tenants require validated cutover' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_bootstrap_insert BEFORE INSERT ON inventory_reference_bootstrap
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_bootstrap_guard();

CREATE FUNCTION warehouse_reference_bootstrap_commit_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id
        AND state='ENFORCED' AND initialization_kind='NEW_EMPTY' AND workflow_mode='REFERENCE'
        AND epoch>=NEW.resulting_epoch AND draining_from_epoch=0) THEN
        RAISE EXCEPTION 'reference bootstrap requires its tenant policy' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_bootstrap_commit AFTER INSERT ON inventory_reference_bootstrap
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_bootstrap_commit_guard();

CREATE FUNCTION warehouse_initialize_reference_tenant(scope uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM 1 FROM tenant WHERE id=scope FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'tenant does not exist' USING ERRCODE='23503'; END IF;
    IF EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=scope) THEN RETURN; END IF;
    INSERT INTO inventory_reference_bootstrap(tenant_id,resulting_epoch) VALUES(scope,1);
    INSERT INTO inventory_tenant_cutover(id,tenant_id,state,initialization_kind,activation_at,
        workflow_mode,epoch,draining_from_epoch)
        VALUES(gen_random_uuid(),scope,'ENFORCED','NEW_EMPTY',warehouse_activation_at(),'REFERENCE',1,0);
    SET CONSTRAINTS warehouse_bootstrap_commit IMMEDIATE;
    SET CONSTRAINTS warehouse_bootstrap_commit DEFERRED;
END $$;
DO $$ BEGIN
    EXECUTE format('ALTER FUNCTION warehouse_initialize_reference_tenant(uuid) SET search_path TO pg_catalog,%I,pg_temp',current_schema());
    REVOKE ALL ON FUNCTION warehouse_initialize_reference_tenant(uuid) FROM PUBLIC;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE INSERT,UPDATE,DELETE ON inventory_reference_bootstrap FROM warehouse_app;
        GRANT SELECT ON inventory_reference_bootstrap TO warehouse_app;
        GRANT EXECUTE ON FUNCTION warehouse_initialize_reference_tenant(uuid) TO warehouse_app;
    END IF;
END $$;

DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_workflow_guard()'::regprocedure);
    anchor:='IF NEW.workflow_mode<>''LEGACY'' THEN';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'workflow guard layout changed'; END IF;
    definition:=replace(definition,anchor,'IF NEW.workflow_mode=''REFERENCE'' AND NEW.state=''ENFORCED''
        AND NEW.initialization_kind=''NEW_EMPTY'' AND NEW.epoch=1 AND NEW.draining_from_epoch=0
        AND EXISTS(SELECT FROM inventory_reference_bootstrap WHERE tenant_id=NEW.tenant_id
            AND resulting_epoch=NEW.epoch AND created_xid=pg_current_xact_id()) THEN RETURN NEW; END IF;
        IF NEW.workflow_mode<>''LEGACY'' THEN');
    EXECUTE definition;
END $$;
