ALTER TABLE provisioning_adapter_certification
    ADD COLUMN vendor varchar(120),
    ADD COLUMN status varchar(30),
    ADD COLUMN valid_until timestamptz,
    ADD COLUMN evidence_id uuid,
    ADD COLUMN certified_by uuid,
    ADD COLUMN revoked_by uuid;

UPDATE provisioning_adapter_certification
SET vendor = 'UNKNOWN',
    status = 'PROVISIONAL',
    valid_until = certified_at,
    evidence_id = id,
    certified_by = id
WHERE vendor IS NULL;

ALTER TABLE provisioning_adapter_certification
    ALTER COLUMN vendor SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN valid_until SET NOT NULL,
    ALTER COLUMN evidence_id SET NOT NULL,
    ALTER COLUMN certified_by SET NOT NULL,
    ADD CONSTRAINT ck_provisioning_certification_status
        CHECK (status IN ('CERTIFIED', 'PROVISIONAL', 'UNSUPPORTED', 'REQUIRES_MANUAL')),
    ADD CONSTRAINT ck_provisioning_certification_validity CHECK (valid_until >= certified_at),
    ADD CONSTRAINT ck_provisioning_certification_revocation
        CHECK ((revoked_at IS NULL AND revoked_by IS NULL) OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL));

DROP INDEX uq_provisioning_active_certification;
CREATE UNIQUE INDEX uq_provisioning_active_certification
    ON provisioning_adapter_certification (
        tenant_id, device_kind, device_id, vendor, model, firmware, transport, operation_class
    ) WHERE revoked_at IS NULL;
