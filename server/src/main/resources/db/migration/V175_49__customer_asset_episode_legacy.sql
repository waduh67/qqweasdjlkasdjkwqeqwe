ALTER TABLE onu
    ADD COLUMN asset_id uuid,
    ADD COLUMN assignment_id uuid,
    ADD COLUMN canonical_serial text,
    ADD COLUMN canonical_serial_candidate text,
    ADD COLUMN started_at timestamptz,
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN original_customer_id uuid,
    ADD COLUMN original_topology jsonb,
    ADD COLUMN provenance text NOT NULL DEFAULT 'UNKNOWN' CHECK (provenance IN ('RECEIPT','OPENING_BALANCE','UNKNOWN')),
    ADD COLUMN warehouse_admission text NOT NULL DEFAULT 'LEGACY_UNRESOLVED' CHECK (warehouse_admission IN ('VERIFIED','LEGACY_UNRESOLVED')),
    ADD COLUMN episode_revision bigint NOT NULL DEFAULT 0 CHECK (episode_revision>=0),
    ADD CONSTRAINT onu_episode_tenant_id_uq UNIQUE (tenant_id,id),
    ADD CONSTRAINT onu_episode_asset_fk FOREIGN KEY (tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    ADD CONSTRAINT onu_episode_assignment_fk FOREIGN KEY (tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT onu_episode_interval_ck CHECK (retired_at IS NULL OR (started_at IS NOT NULL AND retired_at>started_at)),
    ADD CONSTRAINT onu_episode_verified_ck CHECK (warehouse_admission<>'VERIFIED' OR
        (asset_id IS NOT NULL AND assignment_id IS NOT NULL AND canonical_serial IS NOT NULL
         AND canonical_serial=warehouse_canonical_serial(serial_number) AND started_at IS NOT NULL
         AND original_customer_id=customer_id AND original_customer_id IS NOT NULL AND provenance<>'UNKNOWN'));

UPDATE onu SET original_customer_id=customer_id,
    original_topology=jsonb_build_object('odpId',odp_id,'portNumber',odp_port_number,'installedAt',installed_at),
    canonical_serial_candidate=warehouse_canonical_serial(serial_number), started_at=installed_at;

DO $$ DECLARE scope uuid; prior_scope text:=current_setting('app.tenant_id',true);
BEGIN
    FOR scope IN SELECT DISTINCT tenant_id FROM onu LOOP
        PERFORM set_config('app.tenant_id',scope::text,true);
        INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state)
        SELECT gen_random_uuid(),scope,'SERIAL',canonical_serial_candidate,
            CASE WHEN count(*)>1 THEN 'CONFLICT' ELSE 'LEGACY_RESERVED' END
        FROM onu WHERE tenant_id=scope AND canonical_serial_candidate IS NOT NULL
        GROUP BY canonical_serial_candidate ON CONFLICT (tenant_id,identity_type,canonical_value) DO NOTHING;
        INSERT INTO inventory_identity_candidate(id,tenant_id,identity_type,source_table,source_id,raw_value,canonical_value,claim_id)
        SELECT gen_random_uuid(),episode.tenant_id,'SERIAL','onu',episode.id,episode.serial_number,
            episode.canonical_serial_candidate,claim.id FROM onu episode
        LEFT JOIN inventory_identity_claim claim ON claim.tenant_id=episode.tenant_id AND claim.identity_type='SERIAL'
            AND claim.canonical_value=episode.canonical_serial_candidate
        WHERE episode.tenant_id=scope AND NOT EXISTS
            (SELECT FROM inventory_identity_candidate candidate WHERE candidate.tenant_id=scope
                AND candidate.source_table='onu' AND candidate.source_id=episode.id AND candidate.identity_type='SERIAL');
        UPDATE inventory_identity_claim claim SET state='CONFLICT',revision=revision+1
        WHERE claim.tenant_id=scope AND claim.state='LEGACY_RESERVED'
            AND (SELECT count(*) FROM inventory_identity_candidate candidate WHERE candidate.tenant_id=scope AND candidate.claim_id=claim.id)>1;
        SET CONSTRAINTS ALL IMMEDIATE;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(prior_scope,''),true);
END $$;

CREATE UNIQUE INDEX onu_verified_active_serial_uq ON onu(tenant_id,canonical_serial)
    WHERE warehouse_admission='VERIFIED' AND retired_at IS NULL;
CREATE UNIQUE INDEX onu_verified_assignment_uq ON onu(tenant_id,assignment_id) WHERE warehouse_admission='VERIFIED';
CREATE UNIQUE INDEX onu_verified_active_asset_uq ON onu(tenant_id,asset_id)
    WHERE warehouse_admission='VERIFIED' AND retired_at IS NULL;
CREATE INDEX onu_episode_interval_idx ON onu(tenant_id,asset_id,started_at,retired_at);
ALTER TABLE onu DROP CONSTRAINT onu_tenant_id_serial_number_key;
ALTER TABLE onu DROP CONSTRAINT onu_customer_id_fkey;
ALTER TABLE onu ADD CONSTRAINT onu_customer_history_fk FOREIGN KEY (customer_id) REFERENCES customer(id);
ALTER TABLE onu ADD CONSTRAINT onu_customer_tenant_fk FOREIGN KEY (tenant_id,customer_id) REFERENCES customer(tenant_id,id) NOT VALID;
DROP INDEX uq_onu_odp_port;
CREATE UNIQUE INDEX uq_onu_odp_port ON onu(odp_id,odp_port_number) WHERE odp_id IS NOT NULL AND retired_at IS NULL;
ALTER TABLE onu ALTER COLUMN warehouse_admission SET DEFAULT 'VERIFIED';
CREATE TRIGGER warehouse_onu_admission BEFORE INSERT OR UPDATE ON onu FOR EACH ROW EXECUTE FUNCTION warehouse_admission_guard();
CREATE TRIGGER warehouse_onu_append_only BEFORE DELETE ON onu FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
