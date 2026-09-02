ALTER TABLE provisioning_step_attempt DROP CONSTRAINT ck_provisioning_attempt_phase;
ALTER TABLE provisioning_step_attempt ADD CONSTRAINT ck_provisioning_attempt_phase CHECK (
    phase IN ('PREFLIGHT', 'APPLY', 'VERIFY', 'ROLLBACK_CHECK', 'COMPENSATE', 'ROLLBACK_VERIFY')
);
