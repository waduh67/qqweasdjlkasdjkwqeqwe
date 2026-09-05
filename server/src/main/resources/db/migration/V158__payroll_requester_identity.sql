ALTER TABLE payroll_run ADD COLUMN requester_id uuid;
UPDATE payroll_run SET requester_id = '00000000-0000-0000-0000-000000000000' WHERE requester_id IS NULL;
ALTER TABLE payroll_run ALTER COLUMN requester_id SET NOT NULL;
CREATE INDEX payroll_run_requester_idx ON payroll_run (tenant_id, requester_id);
