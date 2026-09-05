ALTER TABLE payroll_operation_outcome ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE payroll_operation_outcome ALTER COLUMN id SET NOT NULL;
ALTER TABLE payroll_operation_outcome ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
