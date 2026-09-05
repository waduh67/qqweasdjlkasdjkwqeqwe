CREATE TABLE evidence_retention_audit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    object_key varchar(300) NOT NULL,
    retention_class varchar(40) NOT NULL,
    outcome varchar(40) NOT NULL,
    worker varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE evidence_retention_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_retention_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence_retention_audit USING (tenant_id = current_setting('app.tenant_id', true)::uuid) WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX ix_evidence_retention_audit_tenant_occurred ON evidence_retention_audit (tenant_id, occurred_at DESC);
