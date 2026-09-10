ALTER TABLE inventory_material_template ADD COLUMN created_xid xid8 NOT NULL DEFAULT pg_current_xact_id();
CREATE TABLE inventory_material_template_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), template_id uuid NOT NULL,
    line_number integer NOT NULL CHECK(line_number>0), sku_id uuid NOT NULL,
    quantity_base bigint NOT NULL CHECK(quantity_base>0), base_unit varchar(2) NOT NULL CHECK(base_unit IN ('EA','MM')),
    continuous_cut boolean NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,template_id,line_number), UNIQUE(tenant_id,template_id,sku_id),
    FOREIGN KEY(tenant_id,template_id) REFERENCES inventory_material_template(tenant_id,id),
    FOREIGN KEY(tenant_id,sku_id,base_unit) REFERENCES inventory_sku(tenant_id,id,base_unit)
);
INSERT INTO inventory_material_template_line(id,tenant_id,template_id,line_number,sku_id,quantity_base,base_unit,continuous_cut)
SELECT (line->>'id')::uuid,template.tenant_id,template.id,(line->>'lineNumber')::integer,
    (line->'sku'->>'id')::uuid,(line->>'quantityBase')::bigint,line->'sku'->>'baseUnit',(line->>'continuousCut')::boolean
FROM inventory_material_template template CROSS JOIN LATERAL jsonb_array_elements(template.snapshot::jsonb) line;
CREATE INDEX inventory_material_template_line_sku ON inventory_material_template_line(tenant_id,sku_id);
ALTER TABLE inventory_material_template_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_material_template_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_material_template_line
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_immutable BEFORE UPDATE OR DELETE ON inventory_material_template_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION warehouse_material_template_line_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT FROM inventory_material_template template CROSS JOIN LATERAL jsonb_array_elements(template.snapshot::jsonb) line
        WHERE template.tenant_id=NEW.tenant_id AND template.id=NEW.template_id AND template.created_xid=pg_current_xact_id()
        AND (line->>'id')::uuid=NEW.id AND (line->>'lineNumber')::integer=NEW.line_number
        AND (line->'sku'->>'id')::uuid=NEW.sku_id AND line->'sku'->>'baseUnit'=NEW.base_unit
        AND (line->>'quantityBase')::bigint=NEW.quantity_base AND (line->>'continuousCut')::boolean=NEW.continuous_cut) THEN
        RAISE EXCEPTION 'template lines must match the sealed publication' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_template_line BEFORE INSERT ON inventory_material_template_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_template_line_guard();
CREATE FUNCTION warehouse_material_template_current_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT FROM inventory_material_template template WHERE template.tenant_id=NEW.tenant_id AND template.id=NEW.template_id
        AND jsonb_array_length(template.snapshot::jsonb)=(SELECT count(*) FROM inventory_material_template_line line
            WHERE line.tenant_id=template.tenant_id AND line.template_id=template.id)) THEN
        RAISE EXCEPTION 'active template requires every immutable line' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_template_current BEFORE INSERT OR UPDATE ON inventory_material_template_current
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_template_current_guard();
CREATE FUNCTION warehouse_material_template_archive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state='ARCHIVED' AND EXISTS(SELECT FROM inventory_material_template_line line
        JOIN inventory_material_template_current current ON current.tenant_id=line.tenant_id AND current.template_id=line.template_id
        WHERE line.tenant_id=NEW.tenant_id AND line.sku_id=NEW.id) THEN
        RAISE EXCEPTION 'SKU has an active material template reference' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_template_archive BEFORE UPDATE ON inventory_sku
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_template_archive_guard();
