ALTER TABLE provisioning_capability_evidence
    ADD CONSTRAINT uq_provisioning_capability_exact_evidence UNIQUE (
        id, tenant_id, device_kind, device_id, vendor, model, firmware, transport, operation_class
    );

ALTER TABLE provisioning_adapter_certification
    ALTER COLUMN evidence_id DROP NOT NULL;

DO $$
DECLARE current_tenant uuid;
BEGIN
    FOR current_tenant IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id', current_tenant::text, true);
        UPDATE provisioning_adapter_certification certification
        SET status = 'PROVISIONAL',
            valid_until = certified_at,
            evidence_id = NULL
        WHERE certification.tenant_id = current_tenant
          AND NOT EXISTS (
              SELECT 1
              FROM provisioning_capability_evidence capability
              WHERE capability.id = certification.evidence_id
                AND capability.tenant_id = certification.tenant_id
                AND capability.device_kind = certification.device_kind
                AND capability.device_id = certification.device_id
                AND capability.vendor = certification.vendor
                AND capability.model = certification.model
                AND capability.firmware = certification.firmware
                AND capability.transport = certification.transport
                AND capability.operation_class = certification.operation_class
          );
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

ALTER TABLE provisioning_adapter_certification
    DROP CONSTRAINT ck_provisioning_certification_validity,
    ADD CONSTRAINT ck_provisioning_certification_validity CHECK (
        valid_until > certified_at
        OR (status = 'PROVISIONAL' AND evidence_id IS NULL AND valid_until = certified_at)
    ),
    ADD CONSTRAINT ck_provisioning_certification_evidence_required CHECK (
        status <> 'CERTIFIED' OR evidence_id IS NOT NULL
    ),
    ADD CONSTRAINT fk_provisioning_certification_exact_evidence FOREIGN KEY (
        evidence_id, tenant_id, device_kind, device_id, vendor, model, firmware, transport, operation_class
    ) REFERENCES provisioning_capability_evidence (
        id, tenant_id, device_kind, device_id, vendor, model, firmware, transport, operation_class
    );
