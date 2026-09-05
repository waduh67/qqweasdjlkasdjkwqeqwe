ALTER TABLE payroll_run ADD COLUMN approval_tiers jsonb NOT NULL DEFAULT '[]'::jsonb;
