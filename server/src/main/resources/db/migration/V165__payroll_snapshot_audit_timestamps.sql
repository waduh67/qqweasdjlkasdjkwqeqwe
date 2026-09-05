ALTER TABLE payroll_calculation_snapshot ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE payroll_calculation_snapshot ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
