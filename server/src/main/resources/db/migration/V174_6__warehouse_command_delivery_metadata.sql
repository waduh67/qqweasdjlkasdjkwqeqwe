CREATE TABLE inventory_command_identity (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    canonical_payload text NOT NULL,
    original_session_id text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_operation(tenant_id,id)
);

CREATE TABLE inventory_outbox_delivery (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    state varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','LEASED','DELIVERED','TERMINAL')),
    lease_owner uuid, lease_token uuid, lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz, last_error varchar(80),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_outbox(tenant_id,id),
    CHECK ((state='LEASED')=(lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK (state='LEASED' OR (lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)),
    CHECK ((state='DELIVERED')=(delivered_at IS NOT NULL))
);
CREATE INDEX inventory_delivery_ready ON inventory_outbox_delivery(tenant_id,next_attempt_at,id) WHERE state='PENDING';
CREATE INDEX inventory_delivery_expired ON inventory_outbox_delivery(tenant_id,lease_until,id) WHERE state='LEASED';
CREATE INDEX inventory_delivery_terminal ON inventory_outbox_delivery(tenant_id,id) WHERE state='TERMINAL';

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_command_identity','inventory_outbox_delivery'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
END $$;
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_command_identity FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER warehouse_revision BEFORE UPDATE ON inventory_outbox_delivery FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();
