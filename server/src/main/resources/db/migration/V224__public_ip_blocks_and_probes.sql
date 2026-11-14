CREATE TABLE network_ip_block (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), network cidr NOT NULL,
    vendor_id uuid, gateway varchar(15) NOT NULL DEFAULT '', notes varchar(2000) NOT NULL DEFAULT '',
    monitoring_enabled boolean NOT NULL DEFAULT false, interval_seconds integer NOT NULL DEFAULT 300 CHECK(interval_seconds=300),
    next_check_at timestamptz NOT NULL DEFAULT now(), revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,network),
    FOREIGN KEY(tenant_id,vendor_id) REFERENCES network_vendor(tenant_id,id),
    CHECK(family(network)=4 AND masklen(network) BETWEEN 20 AND 32)
);
ALTER TABLE network_public_ip ADD COLUMN block_id uuid;
ALTER TABLE network_public_ip ADD COLUMN address_role varchar(16) NOT NULL DEFAULT 'HOST' CHECK(address_role IN ('HOST','NETWORK','BROADCAST'));
ALTER TABLE network_public_ip ADD FOREIGN KEY(tenant_id,block_id) REFERENCES network_ip_block(tenant_id,id);
CREATE INDEX network_ip_block_addresses ON network_public_ip(tenant_id,block_id,address);
ALTER TABLE network_ip_allocation ALTER COLUMN client_id DROP NOT NULL;
ALTER TABLE network_ip_allocation ADD COLUMN used_by varchar(200) NOT NULL DEFAULT '';
ALTER TABLE network_ip_allocation ADD CONSTRAINT network_allocation_one_recipient CHECK(
    (client_id IS NOT NULL AND used_by='') OR (client_id IS NULL AND length(trim(used_by)) BETWEEN 1 AND 200));

CREATE TABLE network_probe_job (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), block_id uuid, actor_id uuid,
    state varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','COMPLETED')),
    created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
    UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,block_id) REFERENCES network_ip_block(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE UNIQUE INDEX network_probe_active_block ON network_probe_job(tenant_id,block_id) WHERE block_id IS NOT NULL AND state<>'COMPLETED';
CREATE TABLE network_probe_item (
    tenant_id uuid NOT NULL, job_id uuid NOT NULL, ip_id uuid NOT NULL, address inet NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RUNNING','COMPLETED')),
    lease_token uuid, lease_until timestamptz, result jsonb,
    PRIMARY KEY(tenant_id,job_id,ip_id),
    FOREIGN KEY(tenant_id,job_id) REFERENCES network_probe_job(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,ip_id) REFERENCES network_public_ip(tenant_id,id) ON DELETE CASCADE
);
CREATE TABLE network_ip_probe (
    tenant_id uuid NOT NULL, ip_id uuid NOT NULL, lease_token uuid, lease_until timestamptz,
    result jsonb, PRIMARY KEY(tenant_id,ip_id),
    FOREIGN KEY(tenant_id,ip_id) REFERENCES network_public_ip(tenant_id,id) ON DELETE CASCADE
);

CREATE FUNCTION network_validate_block() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('b2b:'||NEW.tenant_id,0));
    IF EXISTS(SELECT FROM network_ip_block WHERE tenant_id=NEW.tenant_id AND id<>NEW.id AND network && NEW.network) THEN
        RAISE EXCEPTION 'Subnet bertumpuk dengan blok yang sudah terdaftar' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER network_validate_block BEFORE INSERT OR UPDATE OF network ON network_ip_block FOR EACH ROW EXECUTE FUNCTION network_validate_block();
CREATE FUNCTION network_validate_allocation_host() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    PERFORM 1 FROM network_public_ip WHERE tenant_id=NEW.tenant_id AND id=NEW.ip_id AND address_role='HOST' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Network dan broadcast tidak dapat dialokasikan' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER network_validate_allocation_host BEFORE INSERT OR UPDATE ON network_ip_allocation FOR EACH ROW EXECUTE FUNCTION network_validate_allocation_host();
CREATE FUNCTION network_validate_block_address() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE block_network cidr; expected_role text; BEGIN
    expected_role:='HOST';
    IF NEW.block_id IS NOT NULL THEN
        SELECT network INTO STRICT block_network FROM network_ip_block WHERE tenant_id=NEW.tenant_id AND id=NEW.block_id;
        IF NOT NEW.address <<= block_network THEN RAISE EXCEPTION 'IP berada di luar subnet' USING ERRCODE='23514'; END IF;
        expected_role:=CASE WHEN masklen(block_network)>=31 THEN 'HOST'
            WHEN NEW.address=host(network(block_network))::inet THEN 'NETWORK'
            WHEN NEW.address=host(broadcast(block_network))::inet THEN 'BROADCAST' ELSE 'HOST' END;
    END IF;
    IF NEW.address_role<>expected_role OR expected_role<>'HOST' AND EXISTS(
        SELECT FROM network_ip_allocation WHERE tenant_id=NEW.tenant_id AND ip_id=NEW.id) THEN
        RAISE EXCEPTION 'Peran network/broadcast atau pemakaian IP tidak sesuai subnet' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER network_validate_block_address BEFORE INSERT OR UPDATE OF address,block_id,address_role ON network_public_ip
    FOR EACH ROW EXECUTE FUNCTION network_validate_block_address();
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['network_ip_block','network_probe_job','network_probe_item','network_ip_probe'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
END $$;
