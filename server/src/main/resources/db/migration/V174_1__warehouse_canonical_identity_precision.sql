CREATE FUNCTION warehouse_trim_identity(raw_value text) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT btrim(raw_value, U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000')
$$;

CREATE FUNCTION warehouse_canonical_serial(raw_value text) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT NULLIF(upper(warehouse_trim_identity(raw_value) COLLATE "und-x-icu"),'')
$$;

CREATE OR REPLACE FUNCTION warehouse_canonical_mac(raw_value text) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT CASE WHEN warehouse_trim_identity(raw_value) ~ '^([0-9A-Fa-f]{12}|[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}|[0-9A-Fa-f]{4}(\.[0-9A-Fa-f]{4}){2})$'
        THEN upper(translate(warehouse_trim_identity(raw_value), ':-.', '')) END
$$;

ALTER TABLE inventory_identity_candidate
    ADD COLUMN previous_canonical_value text,
    ADD COLUMN previous_claim_id uuid,
    ADD FOREIGN KEY (tenant_id,previous_claim_id) REFERENCES inventory_identity_claim(tenant_id,id);

CREATE TEMP TABLE warehouse_identity_normalization ON COMMIT DROP AS
SELECT id,tenant_id,identity_type,canonical_value AS previous_canonical_value,claim_id AS previous_claim_id,
    CASE identity_type WHEN 'SERIAL' THEN warehouse_canonical_serial(raw_value) ELSE warehouse_canonical_mac(raw_value) END AS canonical_value
FROM inventory_identity_candidate;

INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state)
SELECT gen_random_uuid(),tenant_id,identity_type,canonical_value,
    CASE WHEN count(*)>1 THEN 'CONFLICT' ELSE 'LEGACY_RESERVED' END
FROM warehouse_identity_normalization WHERE canonical_value IS NOT NULL
GROUP BY tenant_id,identity_type,canonical_value
ON CONFLICT (tenant_id,identity_type,canonical_value) DO UPDATE
SET state=CASE WHEN inventory_identity_claim.state='RETIRED' THEN 'RETIRED'
               WHEN EXCLUDED.state='CONFLICT' THEN 'CONFLICT' ELSE inventory_identity_claim.state END,
    admitted_asset_id=CASE WHEN EXCLUDED.state='CONFLICT' AND inventory_identity_claim.state<>'RETIRED' THEN NULL ELSE inventory_identity_claim.admitted_asset_id END,
    revision=inventory_identity_claim.revision+1,updated_at=now()
WHERE inventory_identity_claim.state IN ('LEGACY_RESERVED','CONFLICT') OR
    EXISTS (SELECT FROM warehouse_identity_normalization normalization WHERE normalization.tenant_id=inventory_identity_claim.tenant_id
        AND normalization.identity_type=inventory_identity_claim.identity_type AND normalization.canonical_value=inventory_identity_claim.canonical_value
        AND normalization.canonical_value IS DISTINCT FROM normalization.previous_canonical_value);

SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE inventory_identity_candidate DISABLE TRIGGER warehouse_candidate_append_only;
UPDATE inventory_identity_candidate candidate
SET previous_canonical_value=normalization.previous_canonical_value,previous_claim_id=normalization.previous_claim_id,
    canonical_value=normalization.canonical_value,claim_id=claim.id,revision=candidate.revision+1,updated_at=now()
FROM warehouse_identity_normalization normalization
LEFT JOIN inventory_identity_claim claim ON claim.tenant_id=normalization.tenant_id AND claim.identity_type=normalization.identity_type
    AND claim.canonical_value=normalization.canonical_value
WHERE candidate.id=normalization.id AND candidate.tenant_id=normalization.tenant_id
    AND candidate.canonical_value IS DISTINCT FROM normalization.canonical_value;
ALTER TABLE inventory_identity_candidate ENABLE TRIGGER warehouse_candidate_append_only;

UPDATE inventory_serialized_asset SET canonical_serial_candidate=warehouse_canonical_serial(serial_number),
    canonical_mac_candidate=warehouse_canonical_mac(mac_address),revision=revision+1,updated_at=now()
WHERE warehouse_admission='LEGACY_UNRESOLVED' AND
    (canonical_serial_candidate IS DISTINCT FROM warehouse_canonical_serial(serial_number) OR
     canonical_mac_candidate IS DISTINCT FROM warehouse_canonical_mac(mac_address));

CREATE OR REPLACE FUNCTION warehouse_asset_claim_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.warehouse_admission='VERIFIED' THEN
        IF NEW.canonical_serial IS DISTINCT FROM warehouse_canonical_serial(NEW.serial_number) OR
           NEW.canonical_mac IS DISTINCT FROM warehouse_canonical_mac(NEW.mac_address) THEN
            RAISE EXCEPTION 'canonical identity does not match raw identity' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (SELECT FROM inventory_identity_claim WHERE tenant_id=NEW.tenant_id AND identity_type='SERIAL'
            AND canonical_value=NEW.canonical_serial AND state IN ('ADMITTED','RETIRED') AND admitted_asset_id=NEW.id) OR
           (NEW.mac_address IS NOT NULL AND (NEW.canonical_mac IS NULL OR NOT EXISTS (SELECT FROM inventory_identity_claim
            WHERE tenant_id=NEW.tenant_id AND identity_type='MAC' AND canonical_value=NEW.canonical_mac AND state IN ('ADMITTED','RETIRED') AND admitted_asset_id=NEW.id))) THEN
            RAISE EXCEPTION 'identity is not admitted to this asset' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
