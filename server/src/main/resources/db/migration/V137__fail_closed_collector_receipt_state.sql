ALTER TABLE provisioning_collector_result_receipt
    ALTER COLUMN state_vlan_ids DROP DEFAULT,
    ALTER COLUMN state_vlan_ids DROP NOT NULL;

DO $$
DECLARE
    tenant_record record;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        UPDATE provisioning_collector_result_receipt
        SET state_vlan_ids = NULL
        WHERE state_vlan_ids = '';
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;
