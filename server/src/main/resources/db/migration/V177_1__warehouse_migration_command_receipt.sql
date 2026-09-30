CREATE TABLE inventory_migration_command (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id),
    kind text NOT NULL CHECK (kind='BEGIN'), operation_key varchar(240) NOT NULL,
    batch_id uuid NOT NULL, actor_id uuid NOT NULL, authority_epoch bigint NOT NULL CHECK (authority_epoch>=0),
    expected_cutover_epoch bigint NOT NULL CHECK (expected_cutover_epoch>=0),
    resulting_cutover_epoch bigint NOT NULL CHECK (resulting_cutover_epoch>0),
    canonical_payload text NOT NULL, payload_hash text NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    original_body text NOT NULL, created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,kind,operation_key), UNIQUE (tenant_id,batch_id,kind),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES inventory_migration_batch(tenant_id,id)
);

CREATE FUNCTION warehouse_migration_command_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE batch inventory_migration_batch%ROWTYPE; cutover inventory_tenant_cutover%ROWTYPE; payload jsonb; body jsonb;
BEGIN
    SELECT * INTO cutover FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id FOR SHARE;
    SELECT * INTO batch FROM inventory_migration_batch WHERE tenant_id=NEW.tenant_id AND id=NEW.batch_id;
    payload:=NEW.canonical_payload::jsonb; body:=NEW.original_body::jsonb;
    IF batch.id IS NULL OR cutover.state<>'VALIDATING' OR cutover.migration_batch_id IS DISTINCT FROM batch.id
        OR cutover.epoch IS DISTINCT FROM NEW.resulting_cutover_epoch OR batch.cutover_epoch<>cutover.epoch
        OR batch.requested_by<>NEW.actor_id
        OR NEW.expected_cutover_epoch NOT IN (NEW.resulting_cutover_epoch,NEW.resulting_cutover_epoch-1)
        OR payload IS DISTINCT FROM jsonb_build_object('expectedEpoch',NEW.expected_cutover_epoch,'expectedPreservationHash',batch.source_hash)
        OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR body->'batch'->>'id' IS DISTINCT FROM batch.id::text
        OR body->'batch'->>'sourceHash' IS DISTINCT FROM batch.source_hash
        OR body->>'preservationHash' IS DISTINCT FROM batch.source_hash
        OR (body->'batch'->>'sourceCount')::bigint IS DISTINCT FROM batch.source_count
        OR (body->'cutover'->>'epoch')::bigint IS DISTINCT FROM cutover.epoch
        OR body->'cutover'->>'state' IS DISTINCT FROM 'VALIDATING'
        OR body->'cutover'->>'tenantId' IS DISTINCT FROM NEW.tenant_id::text THEN
        RAISE EXCEPTION 'migration command requires the captured source and resulting tenant fence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_migration_command_guard BEFORE INSERT ON inventory_migration_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_command_guard();
CREATE TRIGGER warehouse_migration_command_immutable BEFORE UPDATE OR DELETE ON inventory_migration_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
ALTER TABLE inventory_migration_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_migration_command FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_migration_command
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE UPDATE,DELETE ON inventory_migration_command FROM warehouse_app;
        GRANT SELECT,INSERT ON inventory_migration_command TO warehouse_app;
    END IF;
END $$;
