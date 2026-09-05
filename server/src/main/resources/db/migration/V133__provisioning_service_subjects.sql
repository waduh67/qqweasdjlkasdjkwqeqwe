ALTER TABLE provisioning_service_intent
    ADD COLUMN hotspot_site_id uuid;

ALTER TABLE provisioning_service_intent
    ALTER COLUMN subscription_id DROP NOT NULL;

ALTER TABLE provisioning_service_intent
    DROP CONSTRAINT uq_provisioning_intent_subscription;

ALTER TABLE provisioning_service_intent
    ADD CONSTRAINT ck_provisioning_intent_subject_exclusive CHECK (
        (subscription_id IS NOT NULL AND hotspot_site_id IS NULL)
        OR (subscription_id IS NULL AND hotspot_site_id IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_provisioning_intent_subscription
    ON provisioning_service_intent (tenant_id, subscription_id)
    WHERE subscription_id IS NOT NULL;

CREATE UNIQUE INDEX uq_provisioning_intent_hotspot_site
    ON provisioning_service_intent (tenant_id, hotspot_site_id)
    WHERE hotspot_site_id IS NOT NULL;

CREATE OR REPLACE FUNCTION provisioning_guard_intent_lifecycle() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'ILLEGAL_INTENT_INITIAL_STATUS: %', NEW.status USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'INTENT_IMMUTABLE: service intents cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.subscription_id IS DISTINCT FROM OLD.subscription_id
        OR NEW.hotspot_site_id IS DISTINCT FROM OLD.hotspot_site_id
        OR NEW.segment_profile_id IS DISTINCT FROM OLD.segment_profile_id
        OR NEW.encapsulation IS DISTINCT FROM OLD.encapsulation
        OR NEW.dedicated_vlan_id IS DISTINCT FROM OLD.dedicated_vlan_id THEN
        RAISE EXCEPTION 'INTENT_IDENTITY_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
        (OLD.status = 'DRAFT' AND NEW.status IN ('ACTIVE', 'DECOMMISSIONED'))
        OR (OLD.status = 'ACTIVE' AND NEW.status IN ('SUSPENDED', 'DECOMMISSIONED'))
        OR (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE', 'DECOMMISSIONED'))
    ) THEN
        RAISE EXCEPTION 'ILLEGAL_INTENT_TRANSITION: % -> %', OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
