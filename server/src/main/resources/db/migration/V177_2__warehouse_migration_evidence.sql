CREATE FUNCTION warehouse_lock_migration_batch(scope uuid, target uuid) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE current_epoch bigint;
BEGIN
    IF scope IS DISTINCT FROM NULLIF(current_setting('app.tenant_id',true),'')::uuid THEN
        RAISE EXCEPTION 'migration batch requires current tenant' USING ERRCODE='42501';
    END IF;
    SELECT epoch INTO current_epoch FROM inventory_tenant_cutover WHERE tenant_id=scope
        AND state='VALIDATING' AND migration_batch_id=target FOR SHARE;
    IF current_epoch IS NULL THEN
        RAISE EXCEPTION 'migration evidence requires validating tenant' USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(scope::text||'|migration-batch|'||target::text,0));
    IF NOT EXISTS (SELECT FROM inventory_migration_batch WHERE tenant_id=scope AND id=target AND cutover_epoch=current_epoch) THEN
        RAISE EXCEPTION 'captured migration batch required' USING ERRCODE='23514';
    END IF;
    RETURN current_epoch;
END $$;

CREATE TABLE inventory_migration_evidence (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), batch_id uuid NOT NULL, case_id uuid NOT NULL,
    source_hash text NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'), cutover_epoch bigint NOT NULL CHECK (cutover_epoch>0),
    actor_id uuid NOT NULL, label varchar(160) NOT NULL CHECK (btrim(label)<>''),
    content_type text NOT NULL CHECK (content_type IN ('application/pdf','image/png','image/jpeg')),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 15728640),
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'), object_key text NOT NULL UNIQUE,
    operation_key varchar(240) NOT NULL, canonical_payload text NOT NULL,
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'), original_body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_key),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES inventory_migration_batch(tenant_id,id),
    FOREIGN KEY (tenant_id,case_id) REFERENCES inventory_provenance_case(tenant_id,id)
);
CREATE INDEX inventory_migration_evidence_case ON inventory_migration_evidence(tenant_id,batch_id,case_id,created_at,id);

CREATE FUNCTION warehouse_migration_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE body jsonb; payload jsonb;
BEGIN
    IF warehouse_lock_migration_batch(NEW.tenant_id,NEW.batch_id)<>NEW.cutover_epoch
        OR NOT EXISTS (SELECT FROM inventory_provenance_case source JOIN inventory_migration_batch batch
            ON batch.tenant_id=source.tenant_id AND batch.id=NEW.batch_id
            WHERE source.tenant_id=NEW.tenant_id AND source.id=NEW.case_id AND source.source_hash=NEW.source_hash
                AND EXISTS (SELECT FROM jsonb_array_elements(batch.source_manifest) member
                    WHERE member->>'caseId'=source.id::text AND member->>'sourceHash'=source.source_hash)) THEN
        RAISE EXCEPTION 'migration evidence requires captured case and epoch' USING ERRCODE='23514';
    END IF;
    body:=NEW.original_body::jsonb; payload:=NEW.canonical_payload::jsonb;
    IF NEW.object_key IS DISTINCT FROM NEW.tenant_id::text||'/warehouse/migrations/'||NEW.batch_id::text||'/'||NEW.case_id::text||'/'||NEW.id::text
        OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR payload IS DISTINCT FROM jsonb_build_object('batchId',NEW.batch_id,'caseId',NEW.case_id,
            'input',jsonb_build_object('expectedEpoch',NEW.cutover_epoch,'expectedCaseHash',NEW.source_hash,'label',NEW.label),
            'sha256',NEW.sha256,'contentType',NEW.content_type)
        OR body->>'id' IS DISTINCT FROM NEW.id::text OR body->>'batchId' IS DISTINCT FROM NEW.batch_id::text
        OR body->>'caseId' IS DISTINCT FROM NEW.case_id::text OR body->>'sourceHash' IS DISTINCT FROM NEW.source_hash
        OR body->>'label' IS DISTINCT FROM NEW.label OR body->>'contentType' IS DISTINCT FROM NEW.content_type
        OR (body->>'sizeBytes')::bigint IS DISTINCT FROM NEW.size_bytes OR body->>'sha256' IS DISTINCT FROM NEW.sha256
        OR body->>'uploadedBy' IS DISTINCT FROM NEW.actor_id::text OR (body->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'migration evidence requires exact stored file identity and response' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_migration_evidence_guard BEFORE INSERT ON inventory_migration_evidence
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_evidence_guard();
CREATE TRIGGER warehouse_migration_evidence_immutable BEFORE UPDATE OR DELETE ON inventory_migration_evidence
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
ALTER TABLE inventory_migration_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_migration_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_migration_evidence
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE UPDATE,DELETE ON inventory_migration_evidence FROM warehouse_app;
        GRANT SELECT,INSERT ON inventory_migration_evidence TO warehouse_app;
    END IF;
END $$;
