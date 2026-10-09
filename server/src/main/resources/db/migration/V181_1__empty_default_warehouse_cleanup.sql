ALTER TABLE inventory_location ADD COLUMN tenant_default boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX inventory_location_tenant_default_uq ON inventory_location(tenant_id) WHERE tenant_default;
ALTER TABLE inventory_location ADD CONSTRAINT inventory_location_tenant_fk
    FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON DELETE CASCADE NOT VALID;

CREATE FUNCTION warehouse_default_location_marker_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    IF NEW.tenant_default IS DISTINCT FROM OLD.tenant_default THEN
        RAISE EXCEPTION 'default warehouse origin is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_default_location_marker_guard BEFORE UPDATE ON inventory_location
    FOR EACH ROW EXECUTE FUNCTION warehouse_default_location_marker_guard();

CREATE FUNCTION warehouse_default_location_delete_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF NOT OLD.tenant_default OR EXISTS(SELECT FROM tenant WHERE id=OLD.tenant_id) THEN
        RAISE EXCEPTION 'warehouse history is append-only' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END $$;
DROP TRIGGER warehouse_master_no_delete ON inventory_location;
CREATE TRIGGER warehouse_master_no_delete BEFORE DELETE ON inventory_location
    FOR EACH ROW EXECUTE FUNCTION warehouse_default_location_delete_guard();

ALTER TABLE inventory_location_topology_fence DROP CONSTRAINT inventory_location_topology_fence_tenant_id_fkey;
ALTER TABLE inventory_location_topology_fence ADD CONSTRAINT inventory_location_topology_fence_tenant_id_fkey
    FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON DELETE CASCADE;
DROP TRIGGER warehouse_append_only ON inventory_location_topology_fence;
CREATE TRIGGER warehouse_append_only BEFORE DELETE ON inventory_location_topology_fence
    FOR EACH ROW EXECUTE FUNCTION warehouse_tenant_control_delete_guard();
