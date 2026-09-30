ALTER TABLE discovered_onu ADD CONSTRAINT discovered_onu_tenant_identity UNIQUE(tenant_id,id);
CREATE TABLE monitoring_discovery_receipt (
    tenant_id uuid NOT NULL, discovery_id uuid NOT NULL, authorization_id uuid NOT NULL,
    operation_key text NOT NULL, request_hash text NOT NULL, response jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(tenant_id,discovery_id),
    FOREIGN KEY(tenant_id,discovery_id) REFERENCES discovered_onu(tenant_id,id),
    FOREIGN KEY(tenant_id,authorization_id) REFERENCES inventory_deployment_authorization(tenant_id,id)
);
ALTER TABLE monitoring_discovery_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE monitoring_discovery_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON monitoring_discovery_receipt
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER monitoring_discovery_receipt_immutable BEFORE UPDATE OR DELETE ON monitoring_discovery_receipt
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
