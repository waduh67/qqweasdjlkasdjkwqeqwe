ALTER TABLE wo_evidence
    ADD COLUMN IF NOT EXISTS receipt_at timestamptz,
    ADD COLUMN IF NOT EXISTS sha256 varchar(64),
    ADD COLUMN IF NOT EXISTS expected_content_type varchar(100) NOT NULL DEFAULT 'application/octet-stream',
    ADD COLUMN IF NOT EXISTS expected_size_bytes bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS revision_state varchar(20) NOT NULL DEFAULT 'MISSING_OBJECT',
    ADD COLUMN IF NOT EXISTS correction_reason varchar(500);

ALTER TABLE wo_signature
    ADD COLUMN IF NOT EXISTS receipt_at timestamptz,
    ADD COLUMN IF NOT EXISTS sha256 varchar(64),
    ADD COLUMN IF NOT EXISTS expected_content_type varchar(100) NOT NULL DEFAULT 'application/octet-stream',
    ADD COLUMN IF NOT EXISTS expected_size_bytes bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS revision_state varchar(20) NOT NULL DEFAULT 'MISSING_OBJECT',
    ADD COLUMN IF NOT EXISTS correction_reason varchar(500);

ALTER TABLE wo_evidence DISABLE ROW LEVEL SECURITY;
ALTER TABLE wo_signature DISABLE ROW LEVEL SECURITY;

UPDATE wo_evidence SET receipt_at = created_at WHERE receipt_at IS NULL;
UPDATE wo_signature SET receipt_at = created_at WHERE receipt_at IS NULL;
UPDATE wo_evidence SET expected_content_type = content_type, expected_size_bytes = size_bytes WHERE expected_size_bytes = 0;
UPDATE wo_signature SET expected_content_type = content_type, expected_size_bytes = size_bytes WHERE expected_size_bytes = 0;

ALTER TABLE wo_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE wo_signature ENABLE ROW LEVEL SECURITY;

ALTER TABLE wo_evidence
    ALTER COLUMN receipt_at SET DEFAULT now(),
    ALTER COLUMN receipt_at SET NOT NULL;
ALTER TABLE wo_signature
    ALTER COLUMN receipt_at SET DEFAULT now(),
    ALTER COLUMN receipt_at SET NOT NULL;
ALTER TABLE wo_evidence DROP CONSTRAINT IF EXISTS ck_wo_evidence_revision_state;
ALTER TABLE wo_evidence ADD CONSTRAINT ck_wo_evidence_revision_state CHECK (revision_state IN ('PENDING','COMMITTED','ORPHAN_OBJECT','MISSING_OBJECT','SUPERSEDED','TOMBSTONED','LEGAL_HOLD'));
ALTER TABLE wo_signature DROP CONSTRAINT IF EXISTS ck_wo_signature_revision_state;
ALTER TABLE wo_signature ADD CONSTRAINT ck_wo_signature_revision_state CHECK (revision_state IN ('PENDING','COMMITTED','ORPHAN_OBJECT','MISSING_OBJECT','SUPERSEDED','TOMBSTONED','LEGAL_HOLD'));
DROP INDEX IF EXISTS uq_wo_signature_work_order;
CREATE INDEX IF NOT EXISTS ix_wo_signature_current ON wo_signature (tenant_id, work_order_id, created_at DESC) WHERE revision_state = 'COMMITTED';

CREATE TABLE IF NOT EXISTS evidence_object_registry (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    revision_id uuid NOT NULL,
    object_key varchar(300) NOT NULL,
    expected_sha256 varchar(64),
    expected_size_bytes bigint NOT NULL,
    expected_content_type varchar(100) NOT NULL,
    actor_id uuid NOT NULL,
    retention_class varchar(40) NOT NULL DEFAULT 'RAW_EVIDENCE_24M',
    state varchar(20) NOT NULL DEFAULT 'PENDING',
    etag varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, revision_id),
    UNIQUE (tenant_id, object_key),
    CONSTRAINT ck_evidence_registry_state CHECK (state IN ('PENDING','COMMITTED','ORPHAN_OBJECT','MISSING_OBJECT','SUPERSEDED','TOMBSTONED','LEGAL_HOLD'))
);
CREATE INDEX IF NOT EXISTS ix_evidence_registry_scan ON evidence_object_registry (tenant_id, state, updated_at);

DO $$ DECLARE t text; BEGIN
    FOREACH t IN ARRAY ARRAY['wo_evidence','wo_signature','evidence_object_registry'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        IF NOT EXISTS (
            SELECT 1
            FROM pg_policies
            WHERE schemaname = current_schema()
              AND tablename = t
              AND policyname = 'tenant_isolation'
        ) THEN
            EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
        END IF;
    END LOOP;
END $$;
