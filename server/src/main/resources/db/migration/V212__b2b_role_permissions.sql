CREATE TABLE iam_default_role_feature (
    tenant_id uuid NOT NULL REFERENCES tenant(id), role_id uuid NOT NULL, feature varchar(100) NOT NULL,
    PRIMARY KEY(tenant_id,role_id,feature),
    FOREIGN KEY(tenant_id,role_id) REFERENCES role(tenant_id,id) ON DELETE CASCADE
);
ALTER TABLE iam_default_role_feature ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam_default_role_feature FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_default_role_feature
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,DELETE ON iam_default_role_feature TO warehouse_app;
    END IF;
END $$;
