ALTER TABLE inventory_tenant_cutover ADD COLUMN workflow_mode varchar(12) NOT NULL DEFAULT 'LEGACY'
    CHECK (workflow_mode IN ('LEGACY','DRAINING','REFERENCE'));
ALTER TABLE inventory_tenant_cutover ADD COLUMN draining_from_epoch bigint CHECK (draining_from_epoch>=0),
    ADD CHECK ((workflow_mode='LEGACY')=(draining_from_epoch IS NULL)),
    ADD CHECK (draining_from_epoch IS NULL OR draining_from_epoch<epoch);

CREATE FUNCTION warehouse_workflow_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.workflow_mode<>'LEGACY' THEN
            RAISE EXCEPTION 'warehouse workflow must start with legacy writers' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.workflow_mode IS DISTINCT FROM OLD.workflow_mode THEN
        IF NEW.state IS DISTINCT FROM OLD.state
            OR NEW.migration_batch_id IS DISTINCT FROM OLD.migration_batch_id
            OR NEW.snapshot_watermark IS DISTINCT FROM OLD.snapshot_watermark
            OR NEW.pending_legacy_effect_ids IS DISTINCT FROM OLD.pending_legacy_effect_ids THEN
            RAISE EXCEPTION 'workflow transition cannot change the stock safety baseline' USING ERRCODE='23514';
        END IF;
        IF (OLD.workflow_mode,NEW.workflow_mode)<>('LEGACY','DRAINING') THEN
            RAISE EXCEPTION 'reference activation requires reconciled legacy transactions' USING ERRCODE='42501';
        END IF;
        IF NEW.draining_from_epoch IS DISTINCT FROM OLD.epoch THEN
            RAISE EXCEPTION 'draining must capture the exact preceding writer epoch' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP='UPDATE' AND NEW.draining_from_epoch IS DISTINCT FROM OLD.draining_from_epoch THEN
        RAISE EXCEPTION 'draining origin is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER warehouse_workflow BEFORE INSERT OR UPDATE ON inventory_tenant_cutover
    FOR EACH ROW EXECUTE FUNCTION warehouse_workflow_guard();

-- Preserve the safety lifecycle owner, its epoch checks and finalization receipt.
-- Workflow-only transitions use the same tenant row and transaction lock.
DO $$ DECLARE definition text; lifecycle text; finalization text; BEGIN
    definition:=pg_get_functiondef('warehouse_cutover_guard()'::regprocedure);
    lifecycle:='IF (OLD.state,NEW.state) NOT IN ((''LEGACY'',''VALIDATING''),(''VALIDATING'',''ENFORCED'')) THEN';
    finalization:='IF NEW.state=''ENFORCED'' THEN';
    IF strpos(definition,lifecycle)=0 OR strpos(definition,finalization)=0 THEN
        RAISE EXCEPTION 'cutover lifecycle guard layout changed';
    END IF;
    definition:=replace(definition,lifecycle,'IF NOT (NEW.state=OLD.state AND NEW.workflow_mode<>OLD.workflow_mode)
            AND (OLD.state,NEW.state) NOT IN ((''LEGACY'',''VALIDATING''),(''VALIDATING'',''ENFORCED'')) THEN');
    definition:=replace(definition,finalization,'IF NEW.state=''ENFORCED'' AND NEW.state<>OLD.state THEN');
    EXECUTE definition;
END $$;
