ALTER TABLE customer DISABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    located_count bigint;
    unlocated_count bigint;
BEGIN
    SELECT count(*) INTO located_count FROM customer;
    ALTER TABLE customer ADD COLUMN IF NOT EXISTS location_status varchar(20);
    UPDATE customer
       SET location_status = CASE
           WHEN location IS NULL OR (ST_X(location) = 0 AND ST_Y(location) = 0) THEN 'UNLOCATED'
           ELSE 'LOCATED'
       END,
           location = CASE
               WHEN location IS NOT NULL AND ST_X(location) = 0 AND ST_Y(location) = 0 THEN NULL
               ELSE location
           END;
    SELECT count(*) INTO unlocated_count FROM customer WHERE location_status = 'UNLOCATED';
    IF located_count <> (SELECT count(*) FROM customer) THEN
        RAISE EXCEPTION 'customer backfill count mismatch';
    END IF;
    IF EXISTS (SELECT 1 FROM customer WHERE location_status = 'LOCATED' AND location IS NULL) THEN
        RAISE EXCEPTION 'located customer has no geometry';
    END IF;
    IF EXISTS (SELECT 1 FROM customer WHERE location_status = 'UNLOCATED' AND location IS NOT NULL) THEN
        RAISE EXCEPTION 'unlocated customer still has geometry';
    END IF;
END $$;

ALTER TABLE customer ALTER COLUMN location DROP NOT NULL;
ALTER TABLE customer ALTER COLUMN location_status SET DEFAULT 'UNLOCATED';
ALTER TABLE customer ALTER COLUMN location_status SET NOT NULL;
ALTER TABLE customer ADD CONSTRAINT customer_location_status_ck CHECK (
    (location_status = 'LOCATED' AND location IS NOT NULL)
    OR (location_status = 'UNLOCATED' AND location IS NULL)
);
DROP INDEX IF EXISTS ix_customer_location;
CREATE INDEX IF NOT EXISTS ix_customer_location ON customer USING gist (location) WHERE location IS NOT NULL;
ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer FORCE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS onboarding_import_batch (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    operation_key varchar(200) NOT NULL,
    schema_version integer NOT NULL,
    mode varchar(32) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, operation_key),
    CHECK (mode IN ('VALIDATE_ONLY', 'PENDING_INSTALLATION', 'ALREADY_INSTALLED'))
);

CREATE TABLE IF NOT EXISTS migration_fulfillment_inbox (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    operation_key varchar(200) NOT NULL,
    subscription_id uuid NOT NULL,
    username varchar(100) NOT NULL,
    plan_id uuid NOT NULL,
    nas_id uuid,
    auth_type varchar(20) NOT NULL,
    credential_handle_id uuid,
    state varchar(32) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, operation_key),
    CHECK (state IN ('PENDING', 'APPROVED', 'APPLIED', 'FAILED'))
);

ALTER TABLE onboarding_import_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_import_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY onboarding_import_batch_tenant_isolation ON onboarding_import_batch
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
ALTER TABLE migration_fulfillment_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE migration_fulfillment_inbox FORCE ROW LEVEL SECURITY;
CREATE POLICY migration_fulfillment_inbox_tenant_isolation ON migration_fulfillment_inbox
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
