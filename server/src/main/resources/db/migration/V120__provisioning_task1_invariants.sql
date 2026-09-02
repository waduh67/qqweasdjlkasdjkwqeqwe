CREATE OR REPLACE FUNCTION provisioning_normalized_value_valid(value jsonb, field_name text DEFAULT NULL) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE item record;
BEGIN
    IF field_name IS NOT NULL THEN
        CASE field_name
            WHEN 'interfaces' THEN
                IF jsonb_typeof(value) <> 'array' THEN RETURN false; END IF;
                FOR item IN SELECT element FROM jsonb_array_elements(value) AS entry(element) LOOP
                    IF jsonb_typeof(item.element) <> 'object'
                        OR NOT provisioning_normalized_value_valid(item.element) THEN RETURN false; END IF;
                END LOOP;
                RETURN true;
            WHEN 'name', 'port' THEN
                RETURN jsonb_typeof(value) = 'string'
                    AND (value #>> '{}') ~ '^[A-Za-z0-9._:/-]{1,160}$'
                    AND lower(value #>> '{}') !~ '(password|secret|credential|token|privatekey)';
            WHEN 'configured', 'enabled', 'external' THEN
                RETURN jsonb_typeof(value) = 'boolean';
            WHEN 'vlanId' THEN
                RETURN jsonb_typeof(value) = 'number'
                    AND value::text ~ '^[0-9]{1,4}$'
                    AND value::text::integer BETWEEN 2 AND 4094;
            WHEN 'vlans' THEN
                IF jsonb_typeof(value) <> 'array' THEN RETURN false; END IF;
                FOR item IN SELECT element FROM jsonb_array_elements(value) AS entry(element) LOOP
                    IF jsonb_typeof(item.element) <> 'number'
                        OR item.element::text !~ '^[0-9]{1,4}$'
                        OR item.element::text::integer NOT BETWEEN 2 AND 4094 THEN RETURN false; END IF;
                END LOOP;
                RETURN true;
            ELSE
                RETURN false;
        END CASE;
    END IF;
    CASE jsonb_typeof(value)
        WHEN 'array' THEN
            FOR item IN SELECT element FROM jsonb_array_elements(value) AS entry(element) LOOP
                IF NOT provisioning_normalized_value_valid(item.element) THEN RETURN false; END IF;
            END LOOP;
            RETURN true;
        WHEN 'object' THEN
            FOR item IN SELECT key, val FROM jsonb_each(value) AS entry(key, val) LOOP
                IF NOT provisioning_normalized_value_valid(item.val, item.key) THEN
                    RETURN false;
                END IF;
            END LOOP;
            RETURN true;
        ELSE
            RETURN false;
    END CASE;
END;
$$;

ALTER TABLE provisioning_device_snapshot DROP CONSTRAINT ck_provisioning_snapshot_no_secrets;
ALTER TABLE provisioning_device_snapshot ADD CONSTRAINT ck_provisioning_snapshot_normalized
    CHECK (provisioning_normalized_value_valid(normalized_state));
ALTER TABLE provisioning_device_observation DROP CONSTRAINT ck_provisioning_observation_no_secrets;
ALTER TABLE provisioning_device_observation ADD CONSTRAINT ck_provisioning_observation_normalized
    CHECK (provisioning_normalized_value_valid(normalized_state));
ALTER TABLE provisioning_step_snapshot DROP CONSTRAINT ck_provisioning_step_snapshot_no_secrets;
ALTER TABLE provisioning_step_snapshot ADD CONSTRAINT ck_provisioning_step_snapshot_normalized
    CHECK (provisioning_normalized_value_valid(normalized_state));

CREATE OR REPLACE FUNCTION provisioning_plan_attribute_valid(attribute_key text, attribute_value text) RETURNS boolean
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE attribute_key
        WHEN 'intentId' THEN attribute_value ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        WHEN 'vlanId' THEN CASE
            WHEN attribute_value ~ '^[0-9]{1,4}$' THEN attribute_value::integer BETWEEN 2 AND 4094
            ELSE false
        END
        WHEN 'expectedPreconditionHash' THEN attribute_value ~ '^[a-f0-9]{64}$'
        WHEN 'planPreconditionHash' THEN attribute_value ~ '^[a-f0-9]{64}$'
        WHEN 'interface' THEN attribute_value ~ '^[A-Za-z0-9._:/-]{1,160}$'
        ELSE false
    END
$$;

ALTER TABLE provisioning_step_attribute DROP CONSTRAINT ck_provisioning_attribute_safe;
ALTER TABLE provisioning_step_attribute ADD CONSTRAINT ck_provisioning_attribute_normalized
    CHECK (provisioning_plan_attribute_valid(attribute_key, attribute_value));

CREATE OR REPLACE FUNCTION provisioning_length_prefix(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT octet_length(convert_to(value, 'UTF8'))::text || ':' || value
$$;

CREATE OR REPLACE FUNCTION provisioning_calculate_plan_hash(target_plan_id uuid, target_tenant_id uuid) RETURNS text
LANGUAGE sql STABLE AS $$
    WITH attribute_payload AS (
        SELECT step.id AS step_id,
               COALESCE(string_agg(
                   provisioning_length_prefix(attribute.attribute_key) ||
                   provisioning_length_prefix(attribute.attribute_value),
                   '' ORDER BY attribute.attribute_key
               ), '') AS payload
        FROM provisioning_step step
        LEFT JOIN provisioning_step_attribute attribute
          ON attribute.step_id = step.id AND attribute.tenant_id = step.tenant_id
        WHERE step.plan_id = target_plan_id AND step.tenant_id = target_tenant_id
        GROUP BY step.id
    ), step_payload AS (
        SELECT step.step_order,
               provisioning_length_prefix(step.id::text) ||
               provisioning_length_prefix(step.step_order::text) ||
               provisioning_length_prefix(step.device_kind) ||
               provisioning_length_prefix(step.device_id::text) ||
               provisioning_length_prefix(step.operation) ||
               attribute_payload.payload AS payload
        FROM provisioning_step step
        JOIN attribute_payload ON attribute_payload.step_id = step.id
        WHERE step.plan_id = target_plan_id AND step.tenant_id = target_tenant_id
    )
    SELECT encode(sha256(convert_to(COALESCE(string_agg(
        provisioning_length_prefix(payload), '' ORDER BY step_order
    ), ''), 'UTF8')), 'hex')
    FROM step_payload
$$;

CREATE OR REPLACE FUNCTION provisioning_guard_plan_immutability() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'GENERATED' THEN
            RAISE EXCEPTION 'ILLEGAL_PLAN_INITIAL_STATUS: %', NEW.status USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: plans cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.intent_id IS DISTINCT FROM OLD.intent_id
        OR NEW.revision IS DISTINCT FROM OLD.revision THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: plan identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'GENERATED' AND NEW.status IN ('VALIDATED', 'REJECTED'))
        OR (OLD.status = 'VALIDATED' AND NEW.status = 'SUPERSEDED')
    ) THEN
        RAISE EXCEPTION 'ILLEGAL_PLAN_TRANSITION: % -> %', OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'GENERATED' AND NEW.content_hash IS DISTINCT FROM OLD.content_hash THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: content hash cannot change' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'VALIDATED'
        THEN
        IF NOT EXISTS (
            SELECT 1 FROM provisioning_step WHERE plan_id = NEW.id AND tenant_id = NEW.tenant_id
        ) THEN
            RAISE EXCEPTION 'PLAN_STEPS_EMPTY' USING ERRCODE = '23514';
        END IF;
        IF NEW.content_hash IS DISTINCT FROM provisioning_calculate_plan_hash(NEW.id, NEW.tenant_id) THEN
            RAISE EXCEPTION 'PLAN_CONTENT_HASH_MISMATCH' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER trg_provisioning_plan_immutable ON provisioning_plan;
CREATE TRIGGER trg_provisioning_plan_immutable
BEFORE INSERT OR UPDATE OR DELETE ON provisioning_plan
FOR EACH ROW EXECUTE FUNCTION provisioning_guard_plan_immutability();

CREATE OR REPLACE FUNCTION provisioning_guard_step_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parent_status varchar(20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: plan steps cannot change' USING ERRCODE = '23514';
    END IF;
    SELECT status INTO parent_status FROM provisioning_plan
    WHERE id = NEW.plan_id AND tenant_id = NEW.tenant_id
    FOR SHARE;
    IF parent_status IS DISTINCT FROM 'GENERATED' THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: finalized plan content cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION provisioning_guard_attribute_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parent_status varchar(20);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: plan attributes cannot change' USING ERRCODE = '23514';
    END IF;
    SELECT plan.status INTO parent_status
    FROM provisioning_plan plan
    JOIN provisioning_step step ON step.plan_id = plan.id AND step.tenant_id = plan.tenant_id
    WHERE step.id = NEW.step_id AND step.tenant_id = NEW.tenant_id
    FOR SHARE OF plan;
    IF parent_status IS DISTINCT FROM 'GENERATED' THEN
        RAISE EXCEPTION 'PLAN_IMMUTABLE: finalized plan content cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DO $$
DECLARE tenant_record record;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        UPDATE provisioning_execution execution
        SET intent_id = plan.intent_id
        FROM provisioning_plan plan
        WHERE execution.plan_id = plan.id
          AND execution.tenant_id = tenant_record.id
          AND plan.tenant_id = tenant_record.id
          AND execution.intent_id IS DISTINCT FROM plan.intent_id;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

ALTER TABLE provisioning_execution ALTER COLUMN intent_id SET NOT NULL;

CREATE OR REPLACE FUNCTION provisioning_guard_execution_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE plan_intent_id uuid;
BEGIN
    SELECT intent_id INTO plan_intent_id
    FROM provisioning_plan
    WHERE id = NEW.plan_id AND tenant_id = NEW.tenant_id;
    IF plan_intent_id IS NULL OR (NEW.intent_id IS NOT NULL AND NEW.intent_id <> plan_intent_id) THEN
        RAISE EXCEPTION 'EXECUTION_PLAN_INTENT_MISMATCH' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'QUEUED' OR NEW.detail IS NOT NULL THEN
            RAISE EXCEPTION 'ILLEGAL_EXECUTION_INITIAL_STATE' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.intent_id IS DISTINCT FROM OLD.intent_id
        OR NEW.plan_id IS DISTINCT FROM OLD.plan_id
        OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key THEN
        RAISE EXCEPTION 'EXECUTION_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'QUEUED' AND NEW.status IN ('RUNNING', 'FAILED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN ('VERIFYING', 'ROLLING_BACK', 'FAILED', 'MANUAL_RECONCILIATION'))
        OR (OLD.status = 'VERIFYING' AND NEW.status IN ('SUCCEEDED', 'ROLLING_BACK', 'FAILED', 'MANUAL_RECONCILIATION'))
        OR (OLD.status = 'ROLLING_BACK' AND NEW.status IN ('ROLLED_BACK', 'FAILED', 'MANUAL_RECONCILIATION'))
        OR (OLD.status = 'FAILED' AND NEW.status = 'MANUAL_RECONCILIATION')
    ) THEN
        RAISE EXCEPTION 'ILLEGAL_EXECUTION_TRANSITION: % -> %', OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    IF NEW.status IN ('FAILED', 'MANUAL_RECONCILIATION') AND COALESCE(btrim(NEW.detail), '') = '' THEN
        RAISE EXCEPTION 'EXECUTION_DETAIL_REQUIRED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_provisioning_execution_identity
BEFORE INSERT OR UPDATE ON provisioning_execution
FOR EACH ROW EXECUTE FUNCTION provisioning_guard_execution_identity();

CREATE OR REPLACE FUNCTION provisioning_guard_allocation_safety() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.reference_count <> 0 THEN
            RAISE EXCEPTION 'ALLOCATION_STILL_REFERENCED' USING ERRCODE = '23514';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.pool_id IS DISTINCT FROM OLD.pool_id
        OR NEW.device_kind IS DISTINCT FROM OLD.device_kind
        OR NEW.device_id IS DISTINCT FROM OLD.device_id
        OR NEW.vlan_id IS DISTINCT FROM OLD.vlan_id
        OR NEW.intent_id IS DISTINCT FROM OLD.intent_id THEN
        RAISE EXCEPTION 'ALLOCATION_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF NEW.reference_count IS DISTINCT FROM OLD.reference_count AND pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'ALLOCATION_REFERENCE_COUNT_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF OLD.active = false AND NEW.active = true THEN
        RAISE EXCEPTION 'ALLOCATION_REACTIVATION_FORBIDDEN' USING ERRCODE = '23514';
    END IF;
    IF OLD.active = true AND NEW.active = false AND OLD.reference_count <> 0 THEN
        RAISE EXCEPTION 'ALLOCATION_STILL_REFERENCED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_provisioning_allocation_safety
BEFORE UPDATE OR DELETE ON provisioning_vlan_allocation
FOR EACH ROW EXECUTE FUNCTION provisioning_guard_allocation_safety();

CREATE OR REPLACE FUNCTION provisioning_guard_reference_reassignment() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.allocation_id IS DISTINCT FROM OLD.allocation_id
        OR NEW.reference_kind IS DISTINCT FROM OLD.reference_kind
        OR NEW.reference_id IS DISTINCT FROM OLD.reference_id THEN
        RAISE EXCEPTION 'ALLOCATION_REFERENCE_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_provisioning_reference_immutable
BEFORE UPDATE ON provisioning_vlan_allocation_reference
FOR EACH ROW EXECUTE FUNCTION provisioning_guard_reference_reassignment();
