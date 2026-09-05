CREATE TABLE inventory_approval (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    approval_type varchar(32) NOT NULL,
    amount bigint NOT NULL CHECK (amount >= 0),
    requester_id uuid NOT NULL,
    custodian_id uuid,
    policy_version bigint NOT NULL,
    policy_snapshot jsonb NOT NULL,
    policy_snapshot_hash varchar(128) NOT NULL,
    operation_key varchar(240) NOT NULL,
    operation_hash varchar(128) NOT NULL,
    emergency_reason varchar(500),
    requested_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    CONSTRAINT inventory_approval_operation_uq UNIQUE (tenant_id, operation_key),
    CONSTRAINT inventory_approval_status_ck CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','REWORK_REQUIRED'))
);

CREATE TABLE inventory_approval_decision (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    approval_id uuid NOT NULL REFERENCES inventory_approval(id),
    tier integer NOT NULL CHECK (tier > 0),
    approver_id uuid NOT NULL,
    delegated_from uuid,
    decision varchar(16) NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
    reason varchar(500),
    decided_at timestamptz NOT NULL,
    revision bigint NOT NULL,
    operation_key varchar(240) NOT NULL,
    operation_hash varchar(128) NOT NULL,
    CONSTRAINT inventory_approval_decision_operation_uq UNIQUE (tenant_id, approval_id, operation_key)
);

CREATE TABLE inventory_approval_effect (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    approval_id uuid NOT NULL REFERENCES inventory_approval(id),
    approval_type varchar(32) NOT NULL,
    status varchar(24) NOT NULL,
    movement_id uuid,
    operation_key varchar(240) NOT NULL,
    emitted_at timestamptz NOT NULL,
    CONSTRAINT inventory_approval_effect_uq UNIQUE (tenant_id, approval_id)
);

CREATE TABLE inventory_approval_delegation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    approver_id uuid NOT NULL,
    delegate_id uuid NOT NULL,
    valid_until timestamptz NOT NULL,
    CONSTRAINT inventory_approval_delegation_distinct_ck CHECK (approver_id <> delegate_id)
);

CREATE INDEX inventory_approval_pending_idx ON inventory_approval (tenant_id, status, expires_at);
CREATE INDEX inventory_approval_decision_tenant_idx ON inventory_approval_decision (tenant_id, approval_id, revision);

ALTER TABLE inventory_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_decision FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_effect ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_effect FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_delegation ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_approval_delegation FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_approval_tenant_policy ON inventory_approval USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY inventory_approval_decision_tenant_policy ON inventory_approval_decision USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY inventory_approval_effect_tenant_policy ON inventory_approval_effect USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY inventory_approval_delegation_tenant_policy ON inventory_approval_delegation USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE OR REPLACE FUNCTION reject_inventory_approval_decision_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'inventory approval decisions are immutable';
END $$;
CREATE TRIGGER inventory_approval_decision_immutable BEFORE UPDATE OR DELETE ON inventory_approval_decision FOR EACH ROW EXECUTE FUNCTION reject_inventory_approval_decision_mutation();
