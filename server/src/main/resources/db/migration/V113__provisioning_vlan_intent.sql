CREATE TABLE provisioning_vlan_pool (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    name varchar(120) NOT NULL,
    vlan_start integer NOT NULL,
    vlan_end integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_provisioning_vlan_pool_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT uq_provisioning_vlan_pool_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_vlan_pool_range CHECK (
        vlan_start BETWEEN 2 AND 4094 AND vlan_end BETWEEN 2 AND 4094 AND vlan_start <= vlan_end
    )
);

CREATE TABLE provisioning_vlan_reserved_range (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    pool_id uuid NOT NULL,
    vlan_start integer NOT NULL,
    vlan_end integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_reserved_pool FOREIGN KEY (pool_id, tenant_id)
        REFERENCES provisioning_vlan_pool (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_reserved_range UNIQUE (tenant_id, pool_id, vlan_start, vlan_end),
    CONSTRAINT ck_provisioning_reserved_range CHECK (
        vlan_start BETWEEN 2 AND 4094 AND vlan_end BETWEEN 2 AND 4094 AND vlan_start <= vlan_end
    )
);

CREATE TABLE provisioning_segment_profile (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    name varchar(120) NOT NULL,
    pool_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_segment_pool FOREIGN KEY (pool_id, tenant_id)
        REFERENCES provisioning_vlan_pool (id, tenant_id),
    CONSTRAINT uq_provisioning_segment_name UNIQUE (tenant_id, name),
    CONSTRAINT uq_provisioning_segment_id_tenant UNIQUE (id, tenant_id)
);

CREATE TABLE provisioning_service_intent (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    subscription_id uuid NOT NULL,
    segment_profile_id uuid NOT NULL,
    encapsulation varchar(20) NOT NULL DEFAULT 'SINGLE_TAG',
    dedicated_vlan_id integer,
    status varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_intent_profile FOREIGN KEY (segment_profile_id, tenant_id)
        REFERENCES provisioning_segment_profile (id, tenant_id),
    CONSTRAINT uq_provisioning_intent_subscription UNIQUE (tenant_id, subscription_id),
    CONSTRAINT uq_provisioning_intent_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_intent_encapsulation CHECK (encapsulation = 'SINGLE_TAG'),
    CONSTRAINT ck_provisioning_intent_vlan CHECK (dedicated_vlan_id IS NULL OR dedicated_vlan_id BETWEEN 2 AND 4094),
    CONSTRAINT ck_provisioning_intent_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'DECOMMISSIONED'))
);

CREATE TABLE provisioning_vlan_allocation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    pool_id uuid NOT NULL,
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    vlan_id integer NOT NULL,
    intent_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true,
    reference_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_allocation_pool FOREIGN KEY (pool_id, tenant_id)
        REFERENCES provisioning_vlan_pool (id, tenant_id),
    CONSTRAINT fk_provisioning_allocation_intent FOREIGN KEY (intent_id, tenant_id)
        REFERENCES provisioning_service_intent (id, tenant_id),
    CONSTRAINT uq_provisioning_allocation_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_allocation_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_allocation_vlan CHECK (vlan_id BETWEEN 2 AND 4094),
    CONSTRAINT ck_provisioning_allocation_references CHECK (reference_count >= 0)
);

CREATE UNIQUE INDEX uq_provisioning_active_vlan_scope
    ON provisioning_vlan_allocation (tenant_id, device_kind, device_id, vlan_id) WHERE active;

CREATE TABLE provisioning_vlan_allocation_reference (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    allocation_id uuid NOT NULL,
    reference_kind varchar(40) NOT NULL,
    reference_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_reference_allocation FOREIGN KEY (allocation_id, tenant_id)
        REFERENCES provisioning_vlan_allocation (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_allocation_reference UNIQUE (tenant_id, allocation_id, reference_kind, reference_id)
);

CREATE OR REPLACE FUNCTION provisioning_update_reference_count() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE provisioning_vlan_allocation
        SET reference_count = reference_count + 1, updated_at = now()
        WHERE id = NEW.allocation_id AND tenant_id = NEW.tenant_id;
        RETURN NEW;
    END IF;
    UPDATE provisioning_vlan_allocation
    SET reference_count = reference_count - 1, updated_at = now()
    WHERE id = OLD.allocation_id AND tenant_id = OLD.tenant_id;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_provisioning_reference_count
AFTER INSERT OR DELETE ON provisioning_vlan_allocation_reference
FOR EACH ROW EXECUTE FUNCTION provisioning_update_reference_count();

CREATE TABLE provisioning_plan (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    intent_id uuid NOT NULL,
    revision integer NOT NULL,
    status varchar(20) NOT NULL,
    content_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_plan_intent FOREIGN KEY (intent_id, tenant_id)
        REFERENCES provisioning_service_intent (id, tenant_id),
    CONSTRAINT uq_provisioning_plan_revision UNIQUE (tenant_id, intent_id, revision),
    CONSTRAINT uq_provisioning_plan_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_plan_revision CHECK (revision > 0),
    CONSTRAINT ck_provisioning_plan_hash CHECK (content_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_provisioning_plan_status CHECK (status IN ('GENERATED', 'VALIDATED', 'REJECTED', 'SUPERSEDED'))
);

CREATE TABLE provisioning_step (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    plan_id uuid NOT NULL,
    step_order integer NOT NULL,
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    operation varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_step_plan FOREIGN KEY (plan_id, tenant_id)
        REFERENCES provisioning_plan (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_step_order UNIQUE (tenant_id, plan_id, step_order),
    CONSTRAINT uq_provisioning_step_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_step_order CHECK (step_order > 0),
    CONSTRAINT ck_provisioning_step_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_step_operation CHECK (
        operation IN ('ENSURE_TAGGED_VLAN', 'ENSURE_ACCESS_PORT', 'ENSURE_PPPOE_TERMINATION', 'VERIFY_STATE')
    )
);

CREATE TABLE provisioning_step_attribute (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    step_id uuid NOT NULL,
    attribute_key varchar(80) NOT NULL,
    attribute_value varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_attribute_step FOREIGN KEY (step_id, tenant_id)
        REFERENCES provisioning_step (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_step_attribute UNIQUE (tenant_id, step_id, attribute_key),
    CONSTRAINT ck_provisioning_attribute_safe CHECK (
        lower(regexp_replace(attribute_key, '[^a-zA-Z0-9]', '', 'g')) !~ '(password|secret|credential|token|rawcli|command|script)'
    )
);

CREATE OR REPLACE FUNCTION provisioning_guard_plan_immutability() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision IS DISTINCT FROM OLD.revision OR NEW.content_hash IS DISTINCT FROM OLD.content_hash THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: revision and content hash cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_provisioning_plan_immutable
BEFORE UPDATE ON provisioning_plan
FOR EACH ROW EXECUTE FUNCTION provisioning_guard_plan_immutability();

CREATE OR REPLACE FUNCTION provisioning_guard_step_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parent_status varchar(20);
BEGIN
    SELECT status INTO parent_status FROM provisioning_plan
    WHERE id = COALESCE(NEW.plan_id, OLD.plan_id) AND tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id);
    IF parent_status IS DISTINCT FROM 'GENERATED' THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: validated plan content cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_provisioning_step_immutable
BEFORE INSERT OR UPDATE OR DELETE ON provisioning_step
FOR EACH ROW EXECUTE FUNCTION provisioning_guard_step_mutation();

CREATE OR REPLACE FUNCTION provisioning_guard_attribute_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parent_status varchar(20);
BEGIN
    SELECT p.status INTO parent_status
    FROM provisioning_plan p JOIN provisioning_step s ON s.plan_id = p.id AND s.tenant_id = p.tenant_id
    WHERE s.id = COALESCE(NEW.step_id, OLD.step_id) AND s.tenant_id = COALESCE(NEW.tenant_id, OLD.tenant_id);
    IF parent_status IS DISTINCT FROM 'GENERATED' THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: validated plan content cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_provisioning_attribute_immutable
BEFORE INSERT OR UPDATE OR DELETE ON provisioning_step_attribute
FOR EACH ROW EXECUTE FUNCTION provisioning_guard_attribute_mutation();

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'provisioning_vlan_pool', 'provisioning_vlan_reserved_range', 'provisioning_segment_profile',
        'provisioning_service_intent', 'provisioning_vlan_allocation', 'provisioning_vlan_allocation_reference',
        'provisioning_plan', 'provisioning_step', 'provisioning_step_attribute'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END $$;
