ALTER TABLE provisioning_collector_result_receipt
    ADD COLUMN state_vlan_ids text NOT NULL DEFAULT '';

ALTER TABLE provisioning_service_intent
    ADD COLUMN allocation_mode varchar(20);

UPDATE provisioning_service_intent
SET allocation_mode = CASE WHEN dedicated_vlan_id IS NULL THEN 'SHARED' ELSE 'DEDICATED' END;

ALTER TABLE provisioning_service_intent
    ALTER COLUMN allocation_mode SET NOT NULL,
    ADD CONSTRAINT ck_provisioning_intent_allocation_mode CHECK (allocation_mode IN ('SHARED','DEDICATED')),
    ADD CONSTRAINT ck_provisioning_intent_allocation_shape CHECK (allocation_mode = 'DEDICATED' OR dedicated_vlan_id IS NULL);
