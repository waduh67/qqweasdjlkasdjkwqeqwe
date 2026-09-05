CREATE TABLE payroll_approval_policy (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, policy_version bigint NOT NULL,
    approver_ids jsonb NOT NULL, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CONSTRAINT payroll_approval_policy_version_uq UNIQUE (tenant_id, policy_version)
);
CREATE INDEX payroll_approval_policy_tenant_idx ON payroll_approval_policy (tenant_id, policy_version DESC);
ALTER TABLE payroll_approval_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_approval_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_approval_policy_tenant_policy ON payroll_approval_policy
USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE TRIGGER payroll_approval_policy_append_only BEFORE UPDATE OR DELETE ON payroll_approval_policy FOR EACH ROW EXECUTE FUNCTION payroll_configuration_append_only();
