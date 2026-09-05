CREATE TABLE IF NOT EXISTS order_record (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    customer_id uuid NOT NULL REFERENCES customer(id),
    status varchar(24) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','ACCEPTED','SCHEDULED','FULFILLING','FULFILLED','CANCELLED','REJECTED')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    address_text text NOT NULL, city varchar(120) NOT NULL, postal_code varchar(24) NOT NULL,
    latitude double precision, longitude double precision,
    appointment_starts_at timestamptz, appointment_ends_at timestamptz,
    cancellation_reason varchar(500), rejection_reason varchar(500), last_actor_id uuid,
    last_operation_namespace varchar(120) NOT NULL, last_operation_key varchar(240) NOT NULL,
    last_operation_hash varchar(128) NOT NULL,
    persistence_revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((latitude IS NULL) = (longitude IS NULL)),
    CHECK ((appointment_starts_at IS NULL) = (appointment_ends_at IS NULL)),
    CHECK (appointment_starts_at IS NULL OR appointment_starts_at < appointment_ends_at),
    UNIQUE (tenant_id, id)
);
CREATE TABLE IF NOT EXISTS order_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), order_id uuid NOT NULL REFERENCES order_record(id) ON DELETE CASCADE,
    catalog_item_id uuid NOT NULL, description varchar(300) NOT NULL, quantity integer NOT NULL CHECK (quantity > 0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS order_operation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL, payload_hash varchar(128) NOT NULL, outcome_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, namespace, operation_key)
);
CREATE TABLE IF NOT EXISTS order_audit (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), order_id uuid NOT NULL,
    revision bigint NOT NULL, actor_id uuid, operation_namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL, payload_hash varchar(128) NOT NULL, event_type varchar(120) NOT NULL,
    payload text NOT NULL, occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS order_outbox (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), aggregate_id uuid NOT NULL,
    event_type varchar(120) NOT NULL, payload text NOT NULL, published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_order_record_tenant_customer ON order_record (tenant_id, customer_id, id);
CREATE INDEX IF NOT EXISTS ix_order_record_tenant_status ON order_record (tenant_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_order_line_tenant_order ON order_line (tenant_id, order_id);
CREATE INDEX IF NOT EXISTS ix_order_operation_tenant_created ON order_operation (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_order_outbox_pending ON order_outbox (tenant_id, created_at) WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_order_audit_tenant_time ON order_audit (tenant_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION order_tenant_is_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        RAISE EXCEPTION 'tenant ownership is immutable for order records';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS order_record_tenant_immutable ON order_record;
CREATE TRIGGER order_record_tenant_immutable BEFORE UPDATE ON order_record
FOR EACH ROW EXECUTE FUNCTION order_tenant_is_immutable();

-- Existing installations can carry a legacy table from a local Todo 4 spike.
-- Backfill is owned by migration, bypasses RLS only inside this block, and fails
-- closed if a legacy row cannot be assigned to exactly one tenant.
DO $$ DECLARE legacy_count bigint; backfilled_count bigint;
BEGIN
    IF to_regclass('legacy_order') IS NOT NULL THEN
        SELECT count(*) INTO legacy_count FROM legacy_order;
        ALTER TABLE order_record DISABLE ROW LEVEL SECURITY;
        INSERT INTO order_record (id, tenant_id, customer_id, status, revision, address_text, city, postal_code,
            last_operation_namespace, last_operation_key, last_operation_hash)
        SELECT id, tenant_id, customer_id, status, revision, address_text, city, postal_code,
            'legacy-backfill', id::text, md5(id::text)
        FROM legacy_order ON CONFLICT (id) DO NOTHING;
        GET DIAGNOSTICS backfilled_count = ROW_COUNT;
        IF backfilled_count <> legacy_count THEN
            RAISE EXCEPTION 'order backfill count mismatch: expected %, inserted %', legacy_count, backfilled_count;
        END IF;
    END IF;
END $$;

DO $$ DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['order_record','order_line','order_operation','order_audit','order_outbox'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    END LOOP;
    IF to_regclass('legacy_order') IS NOT NULL THEN
        ALTER TABLE order_record ENABLE ROW LEVEL SECURITY;
        ALTER TABLE order_record FORCE ROW LEVEL SECURITY;
    END IF;
END $$;
