-- Existing service is a monitoring relationship, not a warehouse installation.
ALTER TABLE onu DROP CONSTRAINT onu_warehouse_admission_check;
ALTER TABLE onu ADD CONSTRAINT onu_warehouse_admission_check
    CHECK (warehouse_admission IN ('VERIFIED','LEGACY_UNRESOLVED','MIGRATED'));
ALTER TABLE onu ADD CONSTRAINT onu_migrated_monitoring_only CHECK (warehouse_admission<>'MIGRATED' OR
    (asset_id IS NULL AND assignment_id IS NULL AND provenance='UNKNOWN' AND started_at IS NOT NULL));

CREATE TABLE customer_migration_onu (
    tenant_id uuid NOT NULL,
    onu_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    discovered_id uuid NOT NULL,
    serial_number varchar(64) NOT NULL,
    olt_id uuid,
    olt_code varchar(64) NOT NULL,
    pon_port_label varchar(64),
    observed_at timestamptz NOT NULL,
    linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id,onu_id),
    UNIQUE (tenant_id,serial_number),
    FOREIGN KEY (tenant_id,customer_id) REFERENCES customer_migration(tenant_id,customer_id),
    FOREIGN KEY (tenant_id,onu_id) REFERENCES onu(tenant_id,id)
);
ALTER TABLE customer_migration_onu ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_migration_onu FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_migration_onu
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER customer_migration_onu_immutable BEFORE UPDATE OR DELETE ON customer_migration_onu
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION customer_migrated_onu_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.warehouse_admission='MIGRATED' AND
        (NEW.started_at,NEW.canonical_serial_candidate) IS DISTINCT FROM (OLD.started_at,OLD.canonical_serial_candidate) THEN
        RAISE EXCEPTION 'migration observation identity and start are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER customer_migrated_onu_history BEFORE UPDATE ON onu
    FOR EACH ROW EXECUTE FUNCTION customer_migrated_onu_history_guard();

CREATE FUNCTION customer_migrated_onu_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.warehouse_admission='MIGRATED' AND NOT EXISTS (
        SELECT FROM customer_migration_onu imported JOIN inventory_identity_claim claim
            ON claim.tenant_id=imported.tenant_id AND claim.identity_type='SERIAL'
                AND claim.canonical_value=imported.serial_number AND claim.state='LEGACY_RESERVED'
        WHERE imported.tenant_id=NEW.tenant_id AND imported.onu_id=NEW.id
            AND imported.customer_id=NEW.customer_id AND imported.serial_number=NEW.serial_number
            AND NEW.canonical_serial_candidate=imported.serial_number
    ) THEN RAISE EXCEPTION 'migrated ONU requires recorded source and reserved identity' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER customer_migrated_onu_binding AFTER INSERT OR UPDATE ON onu
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION customer_migrated_onu_binding_guard();

-- Only this narrow owner function may admit a monitoring-only episode. The
-- existing warehouse admission/identity guards stay in force for application SQL.
CREATE FUNCTION customer_link_migrated_onu(target_customer uuid, target_serial text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid;
    serial text:=warehouse_canonical_serial(target_serial);
    observation discovered_onu; existing customer_migration_onu;
    result uuid:=gen_random_uuid(); claim_id uuid:=gen_random_uuid();
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    IF serial IS NULL OR length(serial)>60 THEN
        RAISE EXCEPTION 'Nomor seri ONU tidak valid' USING ERRCODE='P0001';
    END IF;
    PERFORM 1 FROM customer_migration WHERE tenant_id=scope AND customer_id=target_customer FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Pelanggan bukan hasil migrasi' USING ERRCODE='P0001'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('customer-migration-onu:'||scope||':'||serial,0));
    SELECT * INTO existing FROM customer_migration_onu WHERE tenant_id=scope AND serial_number=serial;
    IF FOUND THEN
        IF existing.customer_id<>target_customer THEN
            RAISE EXCEPTION 'ONU sudah terhubung ke pelanggan lain' USING ERRCODE='P0001';
        END IF;
        RETURN existing.onu_id;
    END IF;
    SELECT * INTO observation FROM discovered_onu
        WHERE tenant_id=scope AND serial_number=serial FOR UPDATE;
    IF NOT FOUND OR observation.state<>'DISCOVERED' THEN
        RAISE EXCEPTION 'Pilih ONU yang masih tersedia dalam hasil deteksi' USING ERRCODE='P0001';
    END IF;
    IF EXISTS (SELECT FROM onu WHERE tenant_id=scope AND warehouse_canonical_serial(serial_number)=serial)
        OR EXISTS (SELECT FROM inventory_identity_claim WHERE tenant_id=scope AND identity_type='SERIAL' AND canonical_value=serial) THEN
        RAISE EXCEPTION 'ONU sudah tercatat. Periksa pelanggan atau aset gudangnya' USING ERRCODE='P0001';
    END IF;
    INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state)
        VALUES (claim_id,scope,'SERIAL',serial,'LEGACY_RESERVED');
    INSERT INTO onu(id,tenant_id,customer_id,serial_number,canonical_serial_candidate,warehouse_admission,started_at,status)
        VALUES (result,scope,target_customer,serial,serial,'MIGRATED',clock_timestamp(),'PENDING');
    INSERT INTO inventory_identity_candidate(id,tenant_id,claim_id,identity_type,source_table,source_id,raw_value,canonical_value)
        VALUES (gen_random_uuid(),scope,claim_id,'SERIAL','onu',result,serial,serial);
    INSERT INTO customer_migration_onu(tenant_id,onu_id,customer_id,discovered_id,serial_number,olt_id,olt_code,pon_port_label,observed_at)
        VALUES (scope,result,target_customer,observation.id,serial,observation.olt_id,observation.olt_code,
            observation.pon_port_label,observation.last_seen_at);
    UPDATE discovered_onu SET state='PROVISIONED' WHERE tenant_id=scope AND id=observation.id;
    RETURN result;
END $$;
DO $$ BEGIN
    EXECUTE format('ALTER FUNCTION customer_link_migrated_onu(uuid,text) SET search_path TO pg_catalog,%I,pg_temp',current_schema());
END $$;
REVOKE ALL ON FUNCTION customer_link_migrated_onu(uuid,text) FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT EXECUTE ON FUNCTION customer_link_migrated_onu(uuid,text) TO warehouse_app;
        REVOKE ALL ON customer_migration_onu FROM warehouse_app;
        GRANT SELECT ON customer_migration_onu TO warehouse_app;
    END IF;
END $$;
