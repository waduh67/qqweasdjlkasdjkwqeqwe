ALTER TABLE ingest_batch ALTER COLUMN received_at SET DEFAULT clock_timestamp();
ALTER TABLE ingest_batch ADD COLUMN retention_anchor timestamptz NOT NULL DEFAULT clock_timestamp();
