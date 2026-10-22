CREATE TABLE network_vendor (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    name varchar(200) NOT NULL, contact varchar(300) NOT NULL DEFAULT '', notes varchar(2000) NOT NULL DEFAULT '',
    active boolean NOT NULL DEFAULT true, revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    UNIQUE(tenant_id,id), CHECK(length(trim(name)) BETWEEN 2 AND 200)
);
CREATE UNIQUE INDEX network_vendor_name ON network_vendor(tenant_id,lower(name));
CREATE TABLE network_public_ip (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), address inet NOT NULL,
    subnet varchar(15) NOT NULL DEFAULT '', gateway varchar(15) NOT NULL DEFAULT '',
    vendor_id uuid, notes varchar(2000) NOT NULL DEFAULT '', revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,address),
    FOREIGN KEY(tenant_id,vendor_id) REFERENCES network_vendor(tenant_id,id),
    CHECK(family(address)=4 AND masklen(address)=32)
);
CREATE TABLE b2b_client_network (
    tenant_id uuid NOT NULL, client_id uuid NOT NULL, details jsonb NOT NULL,
    main_vendor_id uuid, backup_vendor_id uuid, tunnel_password_ciphertext text,
    PRIMARY KEY(tenant_id,client_id),
    FOREIGN KEY(tenant_id,client_id) REFERENCES b2b_client(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,main_vendor_id) REFERENCES network_vendor(tenant_id,id),
    FOREIGN KEY(tenant_id,backup_vendor_id) REFERENCES network_vendor(tenant_id,id),
    CHECK(jsonb_typeof(details)='object')
);
CREATE TABLE network_ip_allocation (
    tenant_id uuid NOT NULL, ip_id uuid NOT NULL, client_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,ip_id),
    FOREIGN KEY(tenant_id,ip_id) REFERENCES network_public_ip(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,client_id) REFERENCES b2b_client(tenant_id,id) ON DELETE CASCADE
);
CREATE INDEX network_allocation_client ON network_ip_allocation(tenant_id,client_id);
CREATE TABLE network_command (
    tenant_id uuid NOT NULL REFERENCES tenant(id), operation_key uuid NOT NULL, actor_id uuid NOT NULL,
    payload_hash varchar(64) NOT NULL, result jsonb NOT NULL,
    PRIMARY KEY(tenant_id,operation_key), FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['network_vendor','network_public_ip','b2b_client_network','network_ip_allocation','network_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
END $$;
