ALTER TABLE payroll_void ADD COLUMN operation_key varchar(240) NOT NULL DEFAULT '';
ALTER TABLE payroll_void ADD CONSTRAINT payroll_void_operation_uq UNIQUE (tenant_id, operation_key);
