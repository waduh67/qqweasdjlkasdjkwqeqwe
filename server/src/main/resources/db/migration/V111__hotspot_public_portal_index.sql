CREATE TABLE hotspot_public_portal_index (
    portal_id varchar(22) PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    site_id uuid NOT NULL UNIQUE REFERENCES hotspot_site (id),
    nas_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    portal_mode varchar(20) NOT NULL,
    branding_display_name varchar(100),
    branding_logo_url varchar(500)
);

INSERT INTO hotspot_public_portal_index (
    portal_id, tenant_id, site_id, nas_id, name, portal_mode, branding_display_name, branding_logo_url
)
SELECT portal_id, tenant_id, id, nas_id, name, portal_mode, branding_display_name, branding_logo_url FROM hotspot_site;

CREATE FUNCTION sync_hotspot_public_portal_index() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO hotspot_public_portal_index (
        portal_id, tenant_id, site_id, nas_id, name, portal_mode, branding_display_name, branding_logo_url
    ) VALUES (
        NEW.portal_id, NEW.tenant_id, NEW.id, NEW.nas_id, NEW.name, NEW.portal_mode,
        NEW.branding_display_name, NEW.branding_logo_url
    )
    ON CONFLICT (site_id) DO UPDATE SET
        portal_id = EXCLUDED.portal_id,
        tenant_id = EXCLUDED.tenant_id,
        nas_id = EXCLUDED.nas_id,
        name = EXCLUDED.name,
        portal_mode = EXCLUDED.portal_mode,
        branding_display_name = EXCLUDED.branding_display_name,
        branding_logo_url = EXCLUDED.branding_logo_url;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sync_hotspot_public_portal_index
AFTER INSERT OR UPDATE OF portal_id, tenant_id, nas_id, name, portal_mode, branding_display_name, branding_logo_url ON hotspot_site
FOR EACH ROW EXECUTE FUNCTION sync_hotspot_public_portal_index();
