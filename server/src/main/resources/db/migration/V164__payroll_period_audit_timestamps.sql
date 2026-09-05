ALTER TABLE payroll_period ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE payroll_period ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
