ALTER TABLE inventory_sku
    ADD COLUMN category varchar(100),
    ADD COLUMN model varchar(200),
    ADD COLUMN minimum_quantity_base bigint NOT NULL DEFAULT 0 CHECK (minimum_quantity_base >= 0);
ALTER TABLE inventory_location
    ADD COLUMN site_id uuid,
    ADD COLUMN custodian_id uuid,
    ADD FOREIGN KEY (tenant_id,custodian_id) REFERENCES app_user(tenant_id,id);

ALTER TABLE inventory_operation ALTER COLUMN document_id DROP NOT NULL;
ALTER TABLE inventory_operation ADD COLUMN master_kind varchar(16);
ALTER TABLE inventory_operation ADD CONSTRAINT warehouse_operation_binding CHECK (
    (master_kind IS NULL AND document_id IS NOT NULL) OR
    (master_kind IN ('SKU','LOCATION','SUPPLIER') AND document_id IS NULL AND
     namespace='warehouse.master.' || lower(master_kind) || '.' || lower(business_action) AND
     business_action IN ('CREATE','UPDATE','ARCHIVE')));
CREATE UNIQUE INDEX warehouse_master_action_uq ON inventory_operation(tenant_id,master_kind,resource_id,business_action,document_revision)
    WHERE master_kind IS NOT NULL;

CREATE FUNCTION warehouse_master_references(master_table text, master_id uuid, tenant uuid) RETURNS boolean
LANGUAGE plpgsql AS $$
BEGIN
    IF master_table='inventory_sku' THEN
        RETURN EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id=tenant AND sku_id=master_id AND
            (coalesce(quantity_base,quantity)>0 OR warehouse_admission='LEGACY_UNRESOLVED')) OR
            EXISTS (SELECT FROM inventory_reservation WHERE tenant_id=tenant AND sku_id=master_id AND state='OPEN') OR
            EXISTS (SELECT FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
                WHERE line.tenant_id=tenant AND line.sku_id=master_id AND document.state NOT IN ('CLOSED','CANCELLED')) OR
            EXISTS (SELECT FROM inventory_material_plan_line WHERE tenant_id=tenant AND sku_id=master_id) OR
            EXISTS (SELECT FROM inventory_serialized_asset WHERE tenant_id=tenant AND (warehouse_sku_id=master_id OR sku_id=master_id)) OR
            EXISTS (SELECT FROM inventory_lot WHERE tenant_id=tenant AND sku_id=master_id) OR
            EXISTS (SELECT FROM inventory_segment WHERE tenant_id=tenant AND sku_id=master_id AND state='ACTIVE');
    ELSIF master_table='inventory_location' THEN
        RETURN EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id=tenant AND location_id=master_id AND
            (coalesce(quantity_base,quantity)>0 OR warehouse_admission='LEGACY_UNRESOLVED')) OR
            EXISTS (SELECT FROM inventory_reservation WHERE tenant_id=tenant AND location_id=master_id AND state='OPEN') OR
            EXISTS (SELECT FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
                WHERE line.tenant_id=tenant AND (line.location_id=master_id OR line.destination_location_id=master_id) AND document.state NOT IN ('CLOSED','CANCELLED')) OR
            EXISTS (SELECT FROM inventory_serialized_asset WHERE tenant_id=tenant AND location_id=master_id AND
                (warehouse_admission='LEGACY_UNRESOLVED' OR status NOT IN ('CONSUMED','DISPOSED'))) OR
            EXISTS (SELECT FROM inventory_location WHERE tenant_id=tenant AND parent_location_id=master_id AND state='ACTIVE');
    ELSIF master_table='inventory_supplier' THEN
        RETURN EXISTS (SELECT FROM inventory_document WHERE tenant_id=tenant AND supplier_id=master_id AND state NOT IN ('CLOSED','CANCELLED')) OR
            EXISTS (SELECT FROM inventory_lot WHERE tenant_id=tenant AND supplier_id=master_id);
    END IF;
    RAISE EXCEPTION 'unknown master kind' USING ERRCODE='23514';
END $$;

CREATE OR REPLACE FUNCTION warehouse_master_archive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='ARCHIVED' THEN RAISE EXCEPTION 'archived master is immutable' USING ERRCODE='23514'; END IF;
    IF NEW.state='ARCHIVED' AND warehouse_master_references(TG_TABLE_NAME,OLD.id,OLD.tenant_id) THEN
        RAISE EXCEPTION 'master has stock or unresolved references' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_active_master_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE reference_id uuid; reference_state text; old_reference uuid;
BEGIN
    reference_id := (to_jsonb(NEW)->>TG_ARGV[1])::uuid;
    IF reference_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        old_reference := (to_jsonb(OLD)->>TG_ARGV[1])::uuid;
        IF old_reference IS NOT DISTINCT FROM reference_id THEN RETURN NEW; END IF;
    END IF;
    EXECUTE format('SELECT state FROM %I WHERE tenant_id=$1 AND id=$2 FOR SHARE', TG_ARGV[0])
        INTO reference_state USING NEW.tenant_id,reference_id;
    IF reference_state IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'master reference is inaccessible or archived' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

DO $$ DECLARE binding text[];
BEGIN
    FOREACH binding SLICE 1 IN ARRAY ARRAY[
        ['inventory_document','inventory_supplier','supplier_id'],
        ['inventory_document_line','inventory_sku','sku_id'],
        ['inventory_document_line','inventory_location','location_id'],
        ['inventory_document_line','inventory_location','destination_location_id'],
        ['inventory_material_plan_line','inventory_sku','sku_id'],
        ['inventory_uom_conversion','inventory_sku','sku_id'],
        ['inventory_lot','inventory_sku','sku_id'],
        ['inventory_lot','inventory_supplier','supplier_id'],
        ['inventory_segment','inventory_sku','sku_id'],
        ['inventory_reservation','inventory_sku','sku_id'],
        ['inventory_reservation','inventory_location','location_id'],
        ['inventory_serialized_asset','inventory_sku','warehouse_sku_id'],
        ['inventory_serialized_asset','inventory_location','location_id'],
        ['inventory_location','inventory_location','parent_location_id']
    ] LOOP
        EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_active_master_reference(%L,%L)',
            'warehouse_active_'||binding[3],binding[1],binding[2],binding[3]);
    END LOOP;
END $$;
