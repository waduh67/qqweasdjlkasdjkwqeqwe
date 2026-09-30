DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid='site'::regclass AND contype='u'
        AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='site'::regclass AND attname='tenant_id'),
                         (SELECT attnum FROM pg_attribute WHERE attrelid='site'::regclass AND attname='id')]::smallint[]) THEN
        ALTER TABLE site ADD CONSTRAINT warehouse_site_tenant_id_uq UNIQUE (tenant_id,id);
    END IF;
END $$;

ALTER TABLE inventory_location ADD CONSTRAINT warehouse_location_site_fk
    FOREIGN KEY (tenant_id,site_id) REFERENCES site(tenant_id,id) NOT VALID;
CREATE INDEX warehouse_location_site_idx ON inventory_location(tenant_id,site_id) WHERE site_id IS NOT NULL;

CREATE FUNCTION warehouse_location_site_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE site_area uuid; parent_site uuid; parent_area uuid;
BEGIN
    IF NEW.site_id IS NOT NULL THEN
        SELECT area_id INTO site_area FROM site WHERE tenant_id=NEW.tenant_id AND id=NEW.site_id FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'site reference is inaccessible' USING ERRCODE='23503'; END IF;
        IF site_area IS DISTINCT FROM NEW.area_id THEN
            RAISE EXCEPTION 'warehouse location area differs from site' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.parent_location_id IS NOT NULL THEN
        SELECT site_id,area_id INTO parent_site,parent_area FROM inventory_location
            WHERE tenant_id=NEW.tenant_id AND id=NEW.parent_location_id FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'parent reference is inaccessible' USING ERRCODE='23503'; END IF;
        IF parent_area IS DISTINCT FROM NEW.area_id OR
            (parent_site IS NOT NULL AND NEW.site_id IS NOT NULL AND parent_site<>NEW.site_id) THEN
            RAISE EXCEPTION 'warehouse location differs from parent site or area' USING ERRCODE='23514';
        END IF;
    END IF;
    IF TG_OP='UPDATE' AND (NEW.site_id,NEW.area_id) IS DISTINCT FROM (OLD.site_id,OLD.area_id) AND EXISTS (
        SELECT FROM inventory_location child WHERE child.tenant_id=NEW.tenant_id AND child.parent_location_id=NEW.id AND
            (child.area_id IS DISTINCT FROM NEW.area_id OR
             (child.site_id IS NOT NULL AND NEW.site_id IS NOT NULL AND child.site_id<>NEW.site_id))) THEN
        RAISE EXCEPTION 'parent change conflicts with warehouse children' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_location_site_guard BEFORE INSERT OR UPDATE ON inventory_location
    FOR EACH ROW EXECUTE FUNCTION warehouse_location_site_guard();

CREATE FUNCTION warehouse_site_area_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.area_id IS DISTINCT FROM OLD.area_id AND EXISTS (
        SELECT FROM inventory_location WHERE tenant_id=OLD.tenant_id AND site_id=OLD.id) THEN
        RAISE EXCEPTION 'site still referenced by warehouse locations' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_site_area_guard BEFORE UPDATE OF area_id ON site
    FOR EACH ROW EXECUTE FUNCTION warehouse_site_area_guard();
