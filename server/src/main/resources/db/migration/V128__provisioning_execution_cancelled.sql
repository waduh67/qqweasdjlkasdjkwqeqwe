ALTER TABLE provisioning_execution DROP CONSTRAINT ck_provisioning_execution_status;
ALTER TABLE provisioning_execution ADD CONSTRAINT ck_provisioning_execution_status CHECK (status IN (
    'QUEUED', 'RUNNING', 'VERIFYING', 'SUCCEEDED', 'ROLLING_BACK', 'ROLLED_BACK',
    'FAILED', 'MANUAL_RECONCILIATION', 'CANCELLED'
));
