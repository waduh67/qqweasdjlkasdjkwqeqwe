ALTER TABLE ingest_batch DROP CONSTRAINT ingest_batch_pkey;
ALTER TABLE ingest_batch ADD PRIMARY KEY(tenant_id,collector_id,batch_id);
ALTER TABLE ingest_batch ADD CONSTRAINT ingest_batch_collector_tenant_fk
    FOREIGN KEY(collector_id,tenant_id) REFERENCES collector(id,tenant_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE ingest_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingest_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ingest_batch
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER ingest_batch_identity_immutable BEFORE UPDATE ON ingest_batch
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
