-- These two rows are initialized for every tenant, including an empty one.
-- Keep them immutable while their tenant exists so an application cannot reset
-- authorization/cutover epochs. Only the tenant FK cascade may remove them.
CREATE FUNCTION warehouse_tenant_control_delete_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF EXISTS(SELECT FROM tenant WHERE id=OLD.tenant_id) THEN
        RAISE EXCEPTION 'warehouse history is append-only' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END $$;

DROP TRIGGER warehouse_cutover_append_only ON inventory_tenant_cutover;
CREATE TRIGGER warehouse_cutover_append_only BEFORE DELETE ON inventory_tenant_cutover
    FOR EACH ROW EXECUTE FUNCTION warehouse_tenant_control_delete_guard();
DROP TRIGGER warehouse_authorization_append_only ON iam_authorization_epoch;
CREATE TRIGGER warehouse_authorization_append_only BEFORE DELETE ON iam_authorization_epoch
    FOR EACH ROW EXECUTE FUNCTION warehouse_tenant_control_delete_guard();

ALTER TABLE inventory_tenant_cutover DROP CONSTRAINT inventory_tenant_cutover_tenant_id_fkey;
ALTER TABLE inventory_tenant_cutover ADD CONSTRAINT inventory_tenant_cutover_tenant_id_fkey
    FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON DELETE CASCADE;
ALTER TABLE iam_authorization_epoch DROP CONSTRAINT iam_authorization_epoch_tenant_id_fkey;
ALTER TABLE iam_authorization_epoch ADD CONSTRAINT iam_authorization_epoch_tenant_id_fkey
    FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON DELETE CASCADE;
