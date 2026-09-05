CREATE TABLE IF NOT EXISTS fieldservice_gps_point (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    visit_id uuid NOT NULL,
    work_session_id uuid NOT NULL,
    actor_id uuid NOT NULL,
    device_id uuid NOT NULL,
    longitude double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    accuracy_meters double precision NOT NULL CHECK (accuracy_meters > 0),
    provider varchar(40) NOT NULL,
    client_occurred_at timestamptz NOT NULL,
    server_received_at timestamptz NOT NULL,
    mock_indicator boolean NOT NULL DEFAULT false,
    purpose varchar(24) NOT NULL CHECK (purpose IN ('ONSITE','ATTENDANCE')),
    retention_class varchar(32) NOT NULL CHECK (retention_class = 'GPS_90_DAYS'),
    decision varchar(24) NOT NULL CHECK (decision IN ('ACCEPTED','REVIEW_REQUIRED','REJECTED')),
    revision bigint NOT NULL CHECK (revision >= 0),
    operation_namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL,
    payload_hash varchar(128) NOT NULL,
    legal_hold boolean NOT NULL DEFAULT false,
    UNIQUE (tenant_id, operation_namespace, operation_key),
    CHECK (NOT (latitude = 0 AND longitude = 0))
);
CREATE TABLE IF NOT EXISTS fieldservice_gps_purge_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    point_id uuid NOT NULL,
    deleted_at timestamptz NOT NULL DEFAULT now(),
    retention_class varchar(32) NOT NULL,
    legal_hold boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS ix_fieldservice_gps_tenant_session ON fieldservice_gps_point (tenant_id, work_session_id, server_received_at DESC);
CREATE INDEX IF NOT EXISTS ix_fieldservice_gps_retention ON fieldservice_gps_point (tenant_id, server_received_at) WHERE legal_hold = false;
CREATE INDEX IF NOT EXISTS ix_fieldservice_gps_audit ON fieldservice_gps_purge_evidence (tenant_id, deleted_at DESC);
ALTER TABLE fieldservice_gps_point ENABLE ROW LEVEL SECURITY;
ALTER TABLE fieldservice_gps_point FORCE ROW LEVEL SECURITY;
ALTER TABLE fieldservice_gps_purge_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE fieldservice_gps_purge_evidence FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON fieldservice_gps_point;
DROP POLICY IF EXISTS tenant_isolation ON fieldservice_gps_purge_evidence;
CREATE POLICY tenant_isolation ON fieldservice_gps_point USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON fieldservice_gps_purge_evidence USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
