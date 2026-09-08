CREATE TABLE inventory_location_topology_fence (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL UNIQUE REFERENCES tenant(id),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id)
);
ALTER TABLE inventory_location_topology_fence ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_location_topology_fence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_location_topology_fence
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_revision BEFORE UPDATE ON inventory_location_topology_fence
    FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();
CREATE TRIGGER warehouse_append_only BEFORE DELETE ON inventory_location_topology_fence
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE ON inventory_location_topology_fence TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_lock_location_topology(owner_tenant uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(owner_tenant);
    IF owner_tenant IS NULL THEN RAISE EXCEPTION 'location topology requires tenant scope' USING ERRCODE='23514'; END IF;
    INSERT INTO inventory_location_topology_fence(tenant_id) VALUES (owner_tenant)
        ON CONFLICT (tenant_id) DO UPDATE SET revision=inventory_location_topology_fence.revision+1,updated_at=clock_timestamp();
END $$;

CREATE FUNCTION warehouse_location_topology_statement() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_lock_location_topology(NULLIF(current_setting('app.tenant_id',true),'')::uuid);
    RETURN NULL;
END $$;
CREATE TRIGGER warehouse_location_topology_statement BEFORE INSERT OR UPDATE ON inventory_location
    FOR EACH STATEMENT EXECUTE FUNCTION warehouse_location_topology_statement();

CREATE OR REPLACE FUNCTION warehouse_location_site_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.id) IS DISTINCT FROM (OLD.tenant_id,OLD.id) THEN
        RAISE EXCEPTION 'location identity and tenant are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.site_id IS NOT NULL THEN
        PERFORM id FROM site WHERE tenant_id=NEW.tenant_id AND id=NEW.site_id FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'site reference is inaccessible' USING ERRCODE='23503'; END IF;
    END IF;
    IF NEW.parent_location_id IS NOT NULL THEN
        PERFORM id FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=NEW.parent_location_id FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'parent reference is inaccessible' USING ERRCODE='23503'; END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_assert_location_tree(owner_tenant uuid, changed_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(owner_tenant);
    IF EXISTS (
        WITH RECURSIVE descendants(id) AS (
            SELECT id FROM inventory_location WHERE tenant_id=owner_tenant AND id=changed_id
            UNION SELECT child.id FROM inventory_location child JOIN descendants parent ON child.parent_location_id=parent.id
                WHERE child.tenant_id=owner_tenant
        ), ancestry AS (
            SELECT node.id AS origin,node.area_id AS origin_area,node.id,node.parent_location_id,node.site_id,node.area_id,
                ARRAY[node.id] AS path,false AS cycle
            FROM inventory_location node JOIN descendants ON descendants.id=node.id WHERE node.tenant_id=owner_tenant
            UNION ALL SELECT child.origin,child.origin_area,parent.id,parent.parent_location_id,parent.site_id,parent.area_id,
                child.path||parent.id,parent.id=ANY(child.path)
            FROM ancestry child JOIN inventory_location parent ON parent.id=child.parent_location_id
            WHERE parent.tenant_id=owner_tenant AND NOT child.cycle AND cardinality(child.path)<=32
        )
        SELECT origin FROM ancestry LEFT JOIN site ON site.tenant_id=owner_tenant AND site.id=ancestry.site_id
        GROUP BY origin HAVING count(DISTINCT ancestry.site_id)>1 OR bool_or(
            cycle OR cardinality(path)>32 OR ancestry.area_id IS DISTINCT FROM origin_area OR
            (ancestry.site_id IS NOT NULL AND (site.id IS NULL OR site.area_id IS DISTINCT FROM ancestry.area_id)) OR
            (parent_location_id IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_location parent
                WHERE parent.tenant_id=owner_tenant AND parent.id=ancestry.parent_location_id)))
    ) THEN
        RAISE EXCEPTION 'warehouse location ancestry or subtree is inconsistent'
            USING ERRCODE='23514',CONSTRAINT='warehouse_location_ancestry_ck';
    END IF;
END $$;

CREATE FUNCTION warehouse_location_tree_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    PERFORM warehouse_assert_location_tree(NEW.tenant_id,NEW.id);
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_location_tree_consistent AFTER INSERT OR UPDATE ON inventory_location
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_location_tree_guard();
