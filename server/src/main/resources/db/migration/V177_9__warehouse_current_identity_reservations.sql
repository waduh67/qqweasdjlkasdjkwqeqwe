-- A changed source gets a new candidate; never edit the old raw identity history.
DO $$ DECLARE constraint_name text; BEGIN
    SELECT constraint_row.conname INTO STRICT constraint_name FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid='inventory_identity_candidate'::regclass AND constraint_row.contype='u'
            AND (SELECT array_agg(attribute.attname::text ORDER BY member.ordinality)
                FROM unnest(constraint_row.conkey) WITH ORDINALITY member(number,ordinality)
                JOIN pg_attribute attribute ON attribute.attrelid=constraint_row.conrelid AND attribute.attnum=member.number)
                =ARRAY['tenant_id','source_table','source_id','identity_type'];
    EXECUTE format('ALTER TABLE inventory_identity_candidate DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE inventory_identity_candidate ADD UNIQUE(tenant_id,source_table,source_id,identity_type,raw_value);

CREATE VIEW warehouse_current_legacy_identity WITH(security_invoker=true) AS
SELECT source.tenant_id,source.source_table,source.source_id,identity.kind identity_type,identity.raw_value,
    CASE identity.kind WHEN 'SERIAL' THEN warehouse_canonical_serial(identity.raw_value)
        ELSE warehouse_canonical_mac(identity.raw_value) END canonical_value
FROM warehouse_live_provenance_source source CROSS JOIN LATERAL (VALUES
    ('SERIAL',source.source_snapshot->>'serialNumber'),('MAC',source.source_snapshot->>'macAddress')) identity(kind,raw_value)
WHERE source.source_table IN ('inventory_serialized_asset','inventory_serial_tombstone','onu') AND identity.raw_value IS NOT NULL;

CREATE FUNCTION warehouse_reserve_current_identities(target uuid) RETURNS void LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid; identity record; claim inventory_identity_claim;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM 1 FROM inventory_tenant_cutover WHERE tenant_id=scope AND state='VALIDATING' AND migration_batch_id=target FOR UPDATE;
    IF NOT FOUND OR EXISTS(SELECT FROM inventory_migration_batch WHERE tenant_id=scope AND id=target) THEN
        RAISE EXCEPTION 'current identity capture requires initial exclusive validating cutoff' USING ERRCODE='42501';
    END IF;
    FOR identity IN SELECT * FROM warehouse_current_legacy_identity WHERE tenant_id=scope
        ORDER BY identity_type,canonical_value NULLS LAST,source_table,source_id LOOP
        claim:=NULL;
        IF identity.canonical_value IS NOT NULL THEN
            PERFORM pg_advisory_xact_lock(hashtextextended(scope::text||'|receipt-identity|'||identity.identity_type||'|'||identity.canonical_value,0));
            INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state)
                VALUES(gen_random_uuid(),scope,identity.identity_type,identity.canonical_value,'LEGACY_RESERVED')
                ON CONFLICT(tenant_id,identity_type,canonical_value) DO NOTHING;
            SELECT * INTO STRICT claim FROM inventory_identity_claim WHERE tenant_id=scope
                AND identity_type=identity.identity_type AND canonical_value=identity.canonical_value FOR UPDATE;
        END IF;
        INSERT INTO inventory_identity_candidate(id,tenant_id,claim_id,identity_type,source_table,source_id,raw_value,canonical_value)
            VALUES(gen_random_uuid(),scope,claim.id,identity.identity_type,identity.source_table,identity.source_id,identity.raw_value,identity.canonical_value)
            ON CONFLICT(tenant_id,source_table,source_id,identity_type,raw_value) DO NOTHING;
        IF claim.state IN ('LEGACY_RESERVED','CONFLICT') THEN
            IF EXISTS(SELECT FROM warehouse_current_legacy_identity current_identity WHERE current_identity.tenant_id=scope
                AND current_identity.identity_type=identity.identity_type AND current_identity.canonical_value=identity.canonical_value
                AND current_identity.source_table='inventory_serial_tombstone') THEN
                UPDATE inventory_identity_claim SET state='RETIRED',revision=revision+1,updated_at=clock_timestamp()
                    WHERE tenant_id=scope AND id=claim.id;
            ELSIF claim.state='LEGACY_RESERVED' AND (SELECT count(DISTINCT (candidate.source_table,candidate.source_id))
                FROM inventory_identity_candidate candidate WHERE candidate.tenant_id=scope AND candidate.claim_id=claim.id)>1 THEN
                UPDATE inventory_identity_claim SET state='CONFLICT',revision=revision+1,updated_at=clock_timestamp()
                    WHERE tenant_id=scope AND id=claim.id;
            END IF;
        END IF;
    END LOOP;
END $$;
DO $$ BEGIN
    EXECUTE format('ALTER FUNCTION warehouse_reserve_current_identities(uuid) SET search_path TO pg_catalog,%I,pg_temp',current_schema());
    REVOKE ALL ON FUNCTION warehouse_reserve_current_identities(uuid) FROM PUBLIC;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT ON warehouse_current_legacy_identity TO warehouse_app;
        GRANT EXECUTE ON FUNCTION warehouse_reserve_current_identities(uuid) TO warehouse_app;
    END IF;
END $$;

-- Operational telemetry/status remains writable; source identity cannot slip past
-- its captured reservation once the tenant has begun validation.
CREATE FUNCTION warehouse_legacy_identity_cutoff_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE stage text;
BEGIN
    IF OLD.warehouse_admission='LEGACY_UNRESOLVED' AND
        (to_jsonb(NEW)->'serial_number',to_jsonb(NEW)->'mac_address') IS DISTINCT FROM
        (to_jsonb(OLD)->'serial_number',to_jsonb(OLD)->'mac_address') THEN
        PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
        SELECT state INTO stage FROM inventory_tenant_cutover WHERE tenant_id=OLD.tenant_id FOR SHARE;
        IF stage IS DISTINCT FROM 'LEGACY' THEN
            RAISE EXCEPTION 'legacy identity is fixed at the migration cutoff' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_legacy_identity_cutoff BEFORE UPDATE ON inventory_serialized_asset
    FOR EACH ROW EXECUTE FUNCTION warehouse_legacy_identity_cutoff_guard();
CREATE TRIGGER warehouse_legacy_identity_cutoff BEFORE UPDATE ON onu
    FOR EACH ROW EXECUTE FUNCTION warehouse_legacy_identity_cutoff_guard();

-- Historical reservations still block a new receipt. A proven original baseline
-- must reconcile current active peers, not a peer's obsolete raw spelling/value.
DO $$ DECLARE definition text; fragment text; replacement text; BEGIN
    definition:=pg_get_functiondef('warehouse_admit_migration_opening(uuid,uuid,uuid)'::regprocedure);
    fragment:='AND NOT (candidate.source_table=''inventory_serialized_asset'' AND candidate.source_id=stock_id)';
    replacement:='AND EXISTS (SELECT FROM warehouse_current_legacy_identity current_identity WHERE current_identity.tenant_id=scope
                        AND current_identity.source_table=candidate.source_table AND current_identity.source_id=candidate.source_id
                        AND current_identity.identity_type=identity_kind AND current_identity.canonical_value=identity_value)
                    '||fragment;
    IF strpos(definition,fragment)=0 THEN RAISE EXCEPTION 'opening reservation guard layout changed'; END IF;
    EXECUTE replace(definition,fragment,replacement);
END $$;
