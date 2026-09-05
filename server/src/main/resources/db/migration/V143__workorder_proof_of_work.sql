ALTER TABLE work_order ADD COLUMN IF NOT EXISTS completed_by uuid;
ALTER TABLE work_order ADD COLUMN IF NOT EXISTS proof_of_work_hash varchar(64);
